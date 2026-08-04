package com.cloneguard.services;

import com.cloneguard.model.RefactorSession;
import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks a "before/after quality" trend for CloneGuard's refactoring
 * actions, aggregated per SCAN SESSION rather than per individual
 * refactor. A session starts the moment the user runs Tools ->
 * CloneGuard -> Scan Current File (ScanFileAction), and is finalized —
 * written as one data point — the moment the NEXT manual scan begins,
 * but only if at least one refactor was actually applied during the
 * prior session. Scans where the user just looks at results and applies
 * nothing don't produce a data point at all, since there's no
 * before/after change to show on a trend.
 *
 * Individual refactors (Extract Method, Method Delegation, Pull Up,
 * Push Down) call recordRefactor() as they succeed, from
 * ExtractMethodEngine — this class only counts and accumulates, it
 * never triggers a refactor itself and never decides whether one was
 * safe to apply.
 *
 * Persisted as newline-delimited JSON (one RefactorSession object per
 * line) to a ".cloneguard/metrics.jsonl" file inside the project root —
 * the same lightweight append-only log format the professor's original
 * scenario document described for this feature, and consistent with
 * this codebase's existing use of Gson elsewhere (see
 * PythonServerClient.java) rather than introducing a new JSON library.
 *
 * CHANGED: complexity (and the new WMC/CBO/DIT/NOC metrics) are now
 * computed via SciTools Understand (UnderstandMetricsService) rather
 * than the previous in-house PSI-based cyclomatic complexity formula
 * that lived directly in this class. This is a real, meaningful
 * dependency change: every user now needs Understand installed and
 * licensed, with `und` on their PATH, for these numbers to populate at
 * all. If Understand isn't available, the session is still recorded
 * (LOC and refactor-type/clone-type breakdowns still work exactly as
 * before), but understandAvailable=false and every complexity/WMC/CBO/
 * DIT/NOC field is left at 0 rather than showing a misleading real-
 * looking number.
 */
@Service(Service.Level.PROJECT)
public final class MetricsTrackerService {

    private static final Logger LOG = Logger.getInstance(MetricsTrackerService.class);
    private static final String METRICS_DIR = ".cloneguard";
    private static final String METRICS_FILE = "metrics.jsonl";

    private final Project project;
    private final Gson gson = new Gson();
    private final UnderstandMetricsService understandService;

    // Current in-progress session state. Null fileName means no session
    // is currently open. Deliberately private with no getters — these
    // fields are only meaningful mid-session, and nothing outside this
    // class should be reading partial state.
    private String currentFileName;
    // FIX (found live, this session -- confirmed via direct ps aux
    // evidence): the "after" snapshot methods below used to ONLY have
    // this bare filename string to work with, forcing them to re-find
    // the file via FilenameIndex.getVirtualFilesByName() -- IntelliJ's
    // project file INDEX, not a live file handle. Confirmed directly:
    // right after a refactor writes to disk and a second scan triggers
    // finalizeCurrentSessionIfDirty(), the index lookup was returning
    // empty -- zero results, no exception, nothing logged -- meaning
    // the project's index hadn't caught up with the very-recent write
    // yet. This is a genuine race between the write and the index
    // refresh, not something ReadAction wrapping alone can fix, since
    // the index itself was legitimately stale at the moment of the
    // query. Storing the ACTUAL VirtualFile handle from startSession()
    // (when the file was definitely already open and resolvable) and
    // reusing that same handle directly in the after-snapshot, instead
    // of re-deriving it from a name search, sidesteps the index
    // entirely for the common case -- falling back to the old name-
    // based search only if this direct handle somehow becomes invalid
    // (e.g. the file was genuinely deleted or renamed mid-session).
    private VirtualFile currentVirtualFile;
    // FIX (found live, this session -- confirmed via repeated ps aux
    // evidence, second occurrence): even the direct VirtualFile handle
    // above can go stale across a session involving several sequential
    // refactors, each triggering its own "file saved, reindexing..."
    // cycle (visible in idea.log) -- IntelliJ can swap in a new
    // VirtualFile instance across enough of these cycles, silently
    // invalidating the one captured at session start. Storing the raw
    // filesystem PATH alongside the VirtualFile handle gives a second,
    // more robust fallback: LocalFileSystem.refreshAndFindFileByPath()
    // asks the OS directly for the current file at that path, forcing
    // VFS to refresh its knowledge of it if needed -- unlike
    // FilenameIndex, which only reflects whatever the project's index
    // last knew and can visibly lag behind a very recent write.
    private String currentFilePath;
    private int currentLocBefore;
    private UnderstandMetricsService.UnderstandMetrics currentMetricsBefore; // null if Understand unavailable
    private int currentExtractCount;
    private int currentDelegateCount;
    private int currentPullUpCount;
    private int currentPushDownCount;
    private int currentDuplicatedLinesEliminated;
    private int currentType1Count;
    private int currentType2Count;
    private int currentType3Count;
    private int currentType4Count;

    private static final Pattern CLONE_TYPE_PATTERN = Pattern.compile("Type\\s*(\\d)");

    public MetricsTrackerService(Project project) {
        this.project = project;
        this.understandService = project.getService(UnderstandMetricsService.class);
    }

    public static MetricsTrackerService getInstance(Project project) {
        return project.getService(MetricsTrackerService.class);
    }

    /**
     * Called from ScanFileAction at the start of every manual scan.
     * Finalizes and persists the PREVIOUS session first (if it had any
     * refactor activity), then opens a fresh baseline for this new scan.
     *
     * NOTE: this now calls out to the `und` command-line tool, which can
     * take several real seconds (create + add + analyze + metrics
     * export), not the near-instant PSI traversal this used to be.
     * ScanFileAction must run this from a background task (it already
     * wraps its scan in Task.Backgroundable) -- calling this directly on
     * the UI thread would freeze the IDE for the duration of the
     * Understand analysis.
     */
    public synchronized void startSession(PsiFile psiFile) {
        finalizeCurrentSessionIfDirty();

        currentFileName = psiFile.getName();
        currentVirtualFile = ReadAction.compute(() -> psiFile.isValid() ? psiFile.getVirtualFile() : null);
        currentFilePath = (currentVirtualFile != null) ? currentVirtualFile.getPath() : null;
        currentLocBefore = countLines(psiFile.getText());
        currentMetricsBefore = analyzeWithUnderstand(psiFile);
        currentExtractCount = 0;
        currentDelegateCount = 0;
        currentPullUpCount = 0;
        currentPushDownCount = 0;
        currentDuplicatedLinesEliminated = 0;
        currentType1Count = 0;
        currentType2Count = 0;
        currentType3Count = 0;
        currentType4Count = 0;
    }

    /**
     * Convenience overload for Push Down specifically — it has no
     * associated clone type at all (it isn't a duplication fix), so
     * there's nothing to parse a type out of. Delegates to the full
     * overload below with cloneTypeLabel = null.
     */
    public synchronized void recordRefactor(String refactorType, int duplicatedLinesEliminated) {
        recordRefactor(refactorType, duplicatedLinesEliminated, null);
    }

    /**
     * EXTENDED (Scenario 1 dashboard-coverage request): startSession()
     * above always finalizes whatever session was already open and
     * starts a completely fresh one — correct for its one caller
     * (ScanFileAction, where a new manual scan IS a genuine new
     * boundary), but wrong to call from anywhere else, since it would
     * silently discard an in-progress session's already-recorded
     * refactors.
     *
     * This is the safe alternative for Scenario 1's paste-detection
     * flow: a refactor triggered by a paste-notification, with NO prior
     * "Scan Current File" ever run, previously had recordRefactor()
     * silently no-op forever (see that method's own guard) since there
     * was never a baseline to attribute it to. Calling this immediately
     * before such a refactor opens ONE, using the file's current
     * (pre-refactor) state as the baseline — but ONLY if nothing is
     * already open. If a session is already active (because the user
     * DID scan first, or already pasted+refactored something earlier in
     * this same session), this is a complete no-op, so it can never
     * discard in-progress data or reset a baseline mid-session.
     */
    public synchronized void startSessionIfNoneActive(PsiFile psiFile) {
        if (currentFileName != null) return;
        startSession(psiFile);
    }

    /**
     * Called from ExtractMethodEngine immediately after a refactor
     * actually succeeds — never on a refusal/abort, only real, applied
     * changes count. duplicatedLinesEliminated should be the number of
     * lines removed specifically because they were a duplicate copy
     * (the deleted subclass method body for Pull Up, the original
     * duplicate body replaced by a short delegating call for Extract
     * Method / Delegation) — not just any line-count change, so a
     * refactor that happens to add a helper method's own declaration
     * line doesn't understate how much duplication was actually
     * removed. Push Down passes 0 here since it isn't a duplication fix
     * at all — it still counts toward the refactor-type breakdown, just
     * not toward "duplication eliminated."
     *
     * EXTENDED: cloneTypeLabel is the same string ExtractMethodEngine
     * already threads through every call for the confirm-dialog text
     * (e.g. "Type 1 — Exact Clone", "Type 2 (renamed local variables)")
     * -- reused here rather than adding new plumbing, since both
     * Scenario 1 and Scenario 2 already pass a value shaped exactly
     * this way (see CloneType.label). Parsed with a simple "Type N"
     * regex rather than an exact-string match, since the trailing
     * wording differs by call site but always starts the same way.
     * Null for Push Down, which has no associated clone type at all.
     */
    public synchronized void recordRefactor(String refactorType, int duplicatedLinesEliminated, String cloneTypeLabel) {
        if (currentFileName == null) {
            // A refactor happened with no active session (e.g. triggered
            // from Scenario 1's gutter icon rather than a Scenario 2 scan).
            // Nothing to attribute this to yet — silently skip rather than
            // guessing at a baseline that was never actually captured.
            return;
        }
        switch (refactorType) {
            case "extract" -> currentExtractCount++;
            case "delegate" -> currentDelegateCount++;
            case "pullUp" -> currentPullUpCount++;
            case "pushDown" -> currentPushDownCount++;
            default -> LOG.warn("CloneGuard: unknown refactor type for metrics: " + refactorType);
        }
        currentDuplicatedLinesEliminated += Math.max(0, duplicatedLinesEliminated);

        if (cloneTypeLabel != null) {
            Matcher m = CLONE_TYPE_PATTERN.matcher(cloneTypeLabel);
            if (m.find()) {
                switch (m.group(1)) {
                    case "1" -> currentType1Count++;
                    case "2" -> currentType2Count++;
                    case "3" -> currentType3Count++;
                    case "4" -> currentType4Count++;
                    default -> LOG.warn("CloneGuard: unrecognized clone type digit in label: " + cloneTypeLabel);
                }
            }
        }
    }

    /**
     * Explicit finalize, exposed for cases outside the normal
     * scan-to-scan cycle — e.g. if the user closes the project while a
     * session is still open, so in-progress work isn't silently lost.
     */
    public synchronized void finalizeSessionIfActive() {
        finalizeCurrentSessionIfDirty();
    }

    private void finalizeCurrentSessionIfDirty() {
        if (currentFileName == null) return;

        int totalRefactors = currentExtractCount + currentDelegateCount + currentPullUpCount + currentPushDownCount;
        if (totalRefactors == 0) {
            // Nothing was applied this session — don't log a no-op data point.
            currentFileName = null;
            currentVirtualFile = null;
            currentFilePath = null;
            return;
        }

        RefactorSession session = new RefactorSession();
        session.timestamp = System.currentTimeMillis();
        session.fileName = currentFileName;
        session.locBefore = currentLocBefore;
        session.locAfter = currentLocBefore; // overwritten below if re-readable
        session.duplicatedLinesEliminated = currentDuplicatedLinesEliminated;
        session.extractCount = currentExtractCount;
        session.delegateCount = currentDelegateCount;
        session.pullUpCount = currentPullUpCount;
        session.pushDownCount = currentPushDownCount;
        session.type1Count = currentType1Count;
        session.type2Count = currentType2Count;
        session.type3Count = currentType3Count;
        session.type4Count = currentType4Count;

        // "After" LOC is the current on-disk state of the same file, if
        // it's still findable under the project. If the file was renamed
        // or deleted mid-session (unlikely, but possible), fall back to
        // the "before" value so net-change calculations read as zero
        // rather than a misleading negative/garbage number.
        Integer locAfter = tryReadCurrentLineCount(currentFileName, currentVirtualFile, currentFilePath);
        if (locAfter != null) {
            session.locAfter = locAfter;
        }

        UnderstandMetricsService.UnderstandMetrics metricsAfter = tryReadCurrentUnderstandMetrics(currentFileName, currentVirtualFile, currentFilePath);

        if (currentMetricsBefore != null && metricsAfter != null) {
            session.understandAvailable = true;
            session.complexityBefore = currentMetricsBefore.cyclomaticComplexity;
            session.complexityAfter = metricsAfter.cyclomaticComplexity;
            session.wmcBefore = currentMetricsBefore.weightedMethodsPerClass;
            session.wmcAfter = metricsAfter.weightedMethodsPerClass;
            session.cboBefore = currentMetricsBefore.couplingBetweenObjects;
            session.cboAfter = metricsAfter.couplingBetweenObjects;
            session.ditBefore = currentMetricsBefore.depthOfInheritance;
            session.ditAfter = metricsAfter.depthOfInheritance;
            session.nocBefore = currentMetricsBefore.numberOfChildren;
            session.nocAfter = metricsAfter.numberOfChildren;
            session.locUnderstandBefore = currentMetricsBefore.linesOfCode;
            session.locUnderstandAfter = metricsAfter.linesOfCode;
        } else {
            // Understand wasn't available for the before-snapshot, the
            // after-snapshot, or both -- leave understandAvailable false
            // and every complexity/WMC/CBO/DIT/NOC field at its default
            // 0 rather than mixing a real before-value with a missing
            // after-value (or vice versa), which would silently produce
            // a wrong-looking net change.
            session.understandAvailable = false;
        }

        persistSession(session);
        currentFileName = null;
        currentVirtualFile = null;
        currentFilePath = null;
    }

    private Integer tryReadCurrentLineCount(String fileName, VirtualFile directRef, String filePath) {
        try {
            // Same fix as findByName() -- PsiManager.findFile() and
            // psi.getText() are both PSI reads needing an explicit
            // ReadAction now that this runs on a background thread. This
            // whole method is fast (no external process involved), so
            // wrapping it all in one ReadAction is safe and simplest.
            return ReadAction.compute(() -> {
                VirtualFile found = resolveCurrentFile(fileName, directRef, filePath);
                if (found == null) return null;
                PsiFile psi = PsiManager.getInstance(project).findFile(found);
                return (psi != null) ? countLines(psi.getText()) : null;
            });
        } catch (Exception e) {
            LOG.warn("CloneGuard: could not re-read file for metrics 'after' count: " + e.getMessage());
        }
        return null;
    }

    private UnderstandMetricsService.UnderstandMetrics tryReadCurrentUnderstandMetrics(String fileName, VirtualFile directRef, String filePath) {
        try {
            // IMPORTANT: only the PSI/index lookup (finding the file,
            // confirming it's still resolvable) happens inside
            // ReadAction here -- the actual understandService.analyzeFile()
            // call below runs OUTSIDE it, deliberately. That call shells
            // out to `und` and can take several real seconds; holding a
            // read lock for that entire duration would block every other
            // read/write action in the IDE for as long as Understand is
            // running, which is a far worse problem than the one this is
            // fixing.
            //
            // FIX (found live, this session): confirmed via direct CSV
            // comparison -- the "after" WMC the dashboard showed (4)
            // exactly matched the PRE-refactor file's total (findMinimum
            // CC=3 + main CC=1), not the real post-refactor total (6,
            // confirmed by running `und` directly against the saved
            // file). WriteCommandAction updates the in-memory Document
            // immediately, but `und` is an external process that reads
            // the file straight from DISK -- if IntelliJ hadn't yet
            // flushed that document to disk by the time this ran,
            // Understand silently analyzed the stale pre-refactor bytes.
            // Explicitly saving the document first guarantees `und` sees
            // the real, current content.
            //
            // FIX (found live, this session -- confirmed via direct
            // `ps aux | grep "und add"` evidence across TWO separate
            // tests, then confirmed resolved via ground-truth
            // .cloneguard/metrics.jsonl inspection and DIAGNOSTIC
            // logging that has since been removed once the fix was
            // confirmed working end to end): see resolveCurrentFile()'s
            // own javadoc for the full three-tier fallback this now
            // uses -- a single VirtualFile handle alone wasn't durable
            // enough across a session with several sequential refactors.
            VirtualFile foundForSave = ReadAction.compute(() -> resolveCurrentFile(fileName, directRef, filePath));
            if (foundForSave != null) {
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    Document doc = FileDocumentManager.getInstance().getDocument(foundForSave);
                    if (doc != null) {
                        FileDocumentManager.getInstance().saveDocument(doc);
                    }
                });
            }

            String resolvedPath = ReadAction.compute(() -> {
                VirtualFile found = resolveCurrentFile(fileName, directRef, filePath);
                if (found == null) return null;
                PsiFile psi = PsiManager.getInstance(project).findFile(found);
                return (psi != null) ? found.getPath() : null;
            });
            if (resolvedPath == null) return null;
            return understandService.analyzeFile(resolvedPath);
        } catch (Exception e) {
            LOG.warn("CloneGuard: could not re-read file for Understand metrics 'after' snapshot: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Wraps understandService.analyzeFile() with the PsiFile ->
     * absolute-path lookup it needs, and treats a missing VirtualFile
     * (e.g. an in-memory/unsaved file with no real path yet) the same
     * as "Understand unavailable" -- returns null rather than throwing.
     */
    private UnderstandMetricsService.UnderstandMetrics analyzeWithUnderstand(PsiFile psiFile) {
        // Same principle as tryReadCurrentUnderstandMetrics above: only
        // the PSI read (getVirtualFile()) is wrapped in ReadAction; the
        // slow understandService.analyzeFile() call stays outside it.
        String filePath = ReadAction.compute(() -> {
            VirtualFile vf = psiFile.isValid() ? psiFile.getVirtualFile() : null;
            return (vf != null) ? vf.getPath() : null;
        });
        if (filePath == null) return null;
        return understandService.analyzeFile(filePath);
    }

    /**
     * FIX (professor-flagged, 3.2 -- Medium): this used to manually
     * recurse through every directory under each content root
     * (dir.getChildren() on the project root, walking into
     * node_modules/, .git/, build/, and every other massive directory a
     * real project accumulates) with no depth limit at all -- slow on an
     * ordinary project, and a genuine StackOverflowError risk on a
     * deeply nested or symlinked directory tree.
     *
     * Replaced with IntelliJ's own FilenameIndex, which looks up a
     * filename against the IDE's already-built project index instead of
     * walking the filesystem by hand -- no recursion, no stack risk, and
     * it naturally respects whatever directories the project has already
     * excluded from indexing (build output, VCS metadata, etc.), which
     * the old manual walk had no way to skip at all.
     */
    // FIX: found live -- FilenameIndex.getVirtualFilesByName() touches
    // IntelliJ's project index, which requires an explicit ReadAction on
    // ANY thread that doesn't already hold one. This used to be called
    // from startSession(), which ran directly on the EDT (implicit read
    // access historically tolerated there in many platform versions) --
    // now that startSession() correctly runs on a background thread (see
    // ScanFileAction's Task.Backgroundable), this same call has ZERO
    // implicit read access and throws without an explicit wrap.
    private VirtualFile findByName(String name) {
        return ReadAction.compute(() -> {
            Collection<VirtualFile> found = FilenameIndex.getVirtualFilesByName(
                    project, name, GlobalSearchScope.projectScope(project));
            return found.isEmpty() ? null : found.iterator().next();
        });
    }

    /**
     * FIX (found live, this session -- second occurrence, confirmed via
     * repeated ps aux evidence across two separate tests): resolves the
     * file being finalized using three tiers, from most to least
     * reliable, so a single point of staleness in any one of them
     * doesn't silently fail the whole "after" snapshot:
     *   1. The direct VirtualFile handle captured in startSession(), if
     *      it's still valid -- no lookup needed at all, fastest and
     *      most reliable when it holds.
     *   2. LocalFileSystem.refreshAndFindFileByPath() using the raw
     *      filesystem path captured alongside that handle -- asks the
     *      OS directly for the current file at that path and forces
     *      VFS to refresh its knowledge of it, bypassing the project
     *      index entirely. Confirmed to be needed live: a session
     *      involving several sequential refactors (each triggering its
     *      own reindex cycle) was enough for tier 1's captured
     *      VirtualFile to go stale, even though the file's path on disk
     *      never changed.
     *   3. findByName()'s FilenameIndex-based search, kept only as a
     *      last resort for the genuinely rare case where the file was
     *      actually renamed mid-session and neither of the above can
     *      possibly still be correct.
     */
    private VirtualFile resolveCurrentFile(String fileName, VirtualFile directRef, String filePath) {
        if (directRef != null && directRef.isValid()) {
            return directRef;
        }
        if (filePath != null) {
            VirtualFile viaPath = ReadAction.compute(() -> LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath));
            if (viaPath != null && viaPath.isValid()) {
                return viaPath;
            }
        }
        return findByName(fileName);
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Deliberately a simple, transparent metric — total physical
        // line count, not a "smart" non-blank/non-comment heuristic. A
        // cleverer LOC counter is exactly the kind of small text-
        // processing function that produced several of today's other
        // bugs (see get_return_type_shared, normalizeIdentifiers); raw
        // line count has no such edge cases and is easy for anyone
        // reading the dashboard to sanity-check by eye against the file.
        return (int) text.lines().count();
    }

    private void persistSession(RefactorSession session) {
        try {
            String basePath = project.getBasePath();
            if (basePath == null) return;
            Path dir = Paths.get(basePath, METRICS_DIR);
            Files.createDirectories(dir);
            Path file = dir.resolve(METRICS_FILE);
            String line = gson.toJson(session) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("CloneGuard: failed to persist refactor session metrics: " + e.getMessage());
        }
    }

    /**
     * Same as loadAllSessions(), but filtered to just one file's history —
     * this is what the Trend Dashboard actually uses now. Matches on
     * RefactorSession.fileName, which is the simple filename (e.g.
     * "Foo.java"), the same granularity used everywhere else in this
     * class (see tryReadCurrentLineCount()). KNOWN LIMITATION: two
     * different files that happen to share the same simple name in
     * different folders would incorrectly merge into one trend here —
     * not tracked by full path, consistent with the rest of this
     * service's existing simple-name-only approach, but worth knowing
     * about if that scenario ever comes up.
     */
    public List<RefactorSession> loadSessionsForFile(String fileName) {
        List<RefactorSession> filtered = new ArrayList<>();
        if (fileName == null) return filtered;
        for (RefactorSession s : loadAllSessions()) {
            if (fileName.equals(s.fileName)) {
                filtered.add(s);
            }
        }
        return filtered;
    }

    /**
     * Reads every persisted session, oldest first, across ALL files —
     * kept for callers that genuinely want the whole project's history
     * (currently unused by the dashboard itself, which calls
     * loadSessionsForFile() instead, but left available since it's a
     * reasonable thing a future feature might want). Returns an empty
     * list (never null) if the log doesn't exist yet or can't be read.
     */
    public List<RefactorSession> loadAllSessions() {
        List<RefactorSession> sessions = new ArrayList<>();
        try {
            String basePath = project.getBasePath();
            if (basePath == null) return sessions;
            Path file = Paths.get(basePath, METRICS_DIR, METRICS_FILE);
            if (!Files.exists(file)) return sessions;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    RefactorSession s = gson.fromJson(line, RefactorSession.class);
                    if (s != null) sessions.add(s);
                } catch (Exception parseEx) {
                    LOG.warn("CloneGuard: skipping unparseable metrics line: " + parseEx.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("CloneGuard: failed to read refactor session metrics: " + e.getMessage());
        }
        return sessions;
    }

    /**
     * NEW (project-wide dashboard support): averages the Understand
     * metrics across every file in the project that has at least one
     * recorded session, using each file's MOST RECENT session only --
     * same "point-in-time snapshot, not a running total" philosophy
     * already used for the single-file dashboard's latest-session row
     * (see the class-level javadoc above). A file with no sessions at
     * all, or whose latest session has understandAvailable == false,
     * is excluded from the average entirely rather than silently
     * counted as zero -- a file Understand never successfully analyzed
     * has no real number to contribute, and averaging in a zero would
     * misrepresent the whole project's actual complexity as lower than
     * it is.
     */
    public ProjectMetricsAverage getProjectAverageMetrics() {
        List<RefactorSession> all = loadAllSessions();
        // Latest session per file, in the order each file's last session
        // was recorded -- a simple LinkedHashMap-free approach using a
        // plain loop, since sessions are already stored oldest-first and
        // a later entry for the same fileName just overwrites the
        // earlier one as we walk through.
        java.util.Map<String, RefactorSession> latestPerFile = new java.util.LinkedHashMap<>();
        for (RefactorSession s : all) {
            latestPerFile.put(s.fileName, s);
        }

        List<RefactorSession> usable = new ArrayList<>();
        for (RefactorSession s : latestPerFile.values()) {
            if (s.understandAvailable) usable.add(s);
        }

        ProjectMetricsAverage avg = new ProjectMetricsAverage();
        avg.fileCount = latestPerFile.size();
        avg.filesWithUnderstandData = usable.size();
        for (String fn : latestPerFile.keySet()) {
            avg.allFileNames.add(fn);
        }
        for (RefactorSession s : usable) {
            avg.includedFileNames.add(s.fileName);
        }
        if (usable.isEmpty()) return avg;

        long ccBeforeSum = 0, ccAfterSum = 0, wmcBeforeSum = 0, wmcAfterSum = 0;
        long cboBeforeSum = 0, cboAfterSum = 0, ditBeforeSum = 0, ditAfterSum = 0;
        long nocBeforeSum = 0, nocAfterSum = 0, locBeforeSum = 0, locAfterSum = 0;
        for (RefactorSession s : usable) {
            ccBeforeSum += s.complexityBefore;
            ccAfterSum += s.complexityAfter;
            wmcBeforeSum += s.wmcBefore;
            wmcAfterSum += s.wmcAfter;
            cboBeforeSum += s.cboBefore;
            cboAfterSum += s.cboAfter;
            ditBeforeSum += s.ditBefore;
            ditAfterSum += s.ditAfter;
            nocBeforeSum += s.nocBefore;
            nocAfterSum += s.nocAfter;
            locBeforeSum += s.locBefore;
            locAfterSum += s.locAfter;
        }
        int n = usable.size();
        avg.ccBefore = ccBeforeSum / (double) n;
        avg.ccAfter = ccAfterSum / (double) n;
        avg.wmcBefore = wmcBeforeSum / (double) n;
        avg.wmcAfter = wmcAfterSum / (double) n;
        avg.cboBefore = cboBeforeSum / (double) n;
        avg.cboAfter = cboAfterSum / (double) n;
        avg.ditBefore = ditBeforeSum / (double) n;
        avg.ditAfter = ditAfterSum / (double) n;
        avg.nocBefore = nocBeforeSum / (double) n;
        avg.nocAfter = nocAfterSum / (double) n;
        avg.locBefore = locBeforeSum / (double) n;
        avg.locAfter = locAfterSum / (double) n;
        return avg;
    }

    /** Plain result holder for getProjectAverageMetrics() above. */
    public static class ProjectMetricsAverage {
        public int fileCount;
        public int filesWithUnderstandData;
        // NEW (professor-requested follow-up): the actual file names
        // behind each count above -- fileCount alone doesn't tell a
        // viewer WHICH files were included, and if only 3 of 4 files
        // in the project actually contributed to the average, that's
        // meaningfully different information from just "3 of 4".
        public List<String> allFileNames = new ArrayList<>();
        public List<String> includedFileNames = new ArrayList<>();
        public double ccBefore, ccAfter;
        public double wmcBefore, wmcAfter;
        public double cboBefore, cboAfter;
        public double ditBefore, ditAfter;
        public double nocBefore, nocAfter;
        public double locBefore, locAfter;
    }

    // ─────────────────────────────────────────────────────────────────
    // SCENARIO 3 SUPPORT — merging in sessions recorded by GitHub's
    // apply-refactors.yml workflow, so a Pull Request refactor shows up
    // on the same dashboard as local IDE refactors.
    // ─────────────────────────────────────────────────────────────────
    //
    // GitHub Actions has no way to reach into a running IntelliJ
    // instance -- there may not even BE one open when a PR gets its
    // /refactor comment resolved. Instead, apply-refactors.yml commits a
    // small JSON log file directly into the repo alongside the code fix
    // it applies (see PR_METRICS_LOG for the expected path). This method
    // reads that file, if present, and merges its entries into the same
    // persisted-sessions log this service already reads from --
    // deliberately NOT a separate storage mechanism, so loadAllSessions()
    // and getProjectAverageMetrics() automatically include PR-sourced
    // data with no separate merge step needed anywhere else.
    //
    // Called once per IDE session start (see CloneGuardStartupActivity,
    // or wherever this project wires up plugin startup) rather than on
    // every dashboard refresh, since it involves a file read and should
    // only pick up genuinely NEW entries since the last time this ran.
    private static final String PR_METRICS_LOG = ".cloneguard/pr-refactor-log.json";

    public synchronized void importGithubPrSessionsIfPresent() {
        try {
            String basePath = project.getBasePath();
            if (basePath == null) return;
            Path prLogPath = Paths.get(basePath, PR_METRICS_LOG);
            if (!Files.exists(prLogPath)) return;

            List<RefactorSession> prSessions = new ArrayList<>();
            String content = Files.readString(prLogPath, StandardCharsets.UTF_8);
            RefactorSession[] parsed = gson.fromJson(content, RefactorSession[].class);
            if (parsed != null) {
                for (RefactorSession s : parsed) {
                    if (s != null) prSessions.add(s);
                }
            }
            if (prSessions.isEmpty()) return;

            // Avoid re-importing the same PR sessions on every startup:
            // only persist entries whose (timestamp, fileName,
            // pullRequestNumber) triple isn't already present in the
            // existing log. Simple linear dedup check -- this log is
            // expected to stay small (one entry per successfully
            // resolved /refactor comment), so an O(n*m) check here is a
            // non-issue in practice.
            List<RefactorSession> existing = loadAllSessions();
            for (RefactorSession incoming : prSessions) {
                boolean alreadyPresent = existing.stream().anyMatch(e ->
                        e.timestamp == incoming.timestamp
                                && java.util.Objects.equals(e.fileName, incoming.fileName)
                                && e.pullRequestNumber == incoming.pullRequestNumber);
                if (!alreadyPresent) {
                    incoming.source = "github_pr";
                    persistSession(incoming);
                }
            }
        } catch (IOException e) {
            LOG.warn("CloneGuard: failed to import GitHub PR session log: " + e.getMessage());
        } catch (Exception e) {
            LOG.warn("CloneGuard: could not parse GitHub PR session log (malformed JSON?): " + e.getMessage());
        }
    }
}