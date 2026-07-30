package com.cloneguard.services;

import com.cloneguard.model.RefactorSession;
import com.google.gson.Gson;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
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
        Integer locAfter = tryReadCurrentLineCount(currentFileName);
        if (locAfter != null) {
            session.locAfter = locAfter;
        }

        UnderstandMetricsService.UnderstandMetrics metricsAfter = tryReadCurrentUnderstandMetrics(currentFileName);

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
    }

    private Integer tryReadCurrentLineCount(String fileName) {
        try {
            VirtualFile found = findByName(fileName);
            if (found != null) {
                PsiFile psi = PsiManager.getInstance(project).findFile(found);
                if (psi != null) return countLines(psi.getText());
            }
        } catch (Exception e) {
            LOG.warn("CloneGuard: could not re-read file for metrics 'after' count: " + e.getMessage());
        }
        return null;
    }

    private UnderstandMetricsService.UnderstandMetrics tryReadCurrentUnderstandMetrics(String fileName) {
        try {
            VirtualFile found = findByName(fileName);
            if (found != null) {
                PsiFile psi = PsiManager.getInstance(project).findFile(found);
                if (psi != null) return analyzeWithUnderstand(psi);
            }
        } catch (Exception e) {
            LOG.warn("CloneGuard: could not re-read file for Understand metrics 'after' snapshot: " + e.getMessage());
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
        VirtualFile vf = psiFile.getVirtualFile();
        if (vf == null) return null;
        return understandService.analyzeFile(vf.getPath());
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
    private VirtualFile findByName(String name) {
        Collection<VirtualFile> found = FilenameIndex.getVirtualFilesByName(
                project, name, GlobalSearchScope.projectScope(project));
        return found.isEmpty() ? null : found.iterator().next();
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
}