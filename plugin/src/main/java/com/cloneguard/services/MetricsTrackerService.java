package com.cloneguard.services;

import com.cloneguard.model.RefactorSession;
import com.google.gson.Gson;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
 */
@Service(Service.Level.PROJECT)
public final class MetricsTrackerService {

    private static final Logger LOG = Logger.getInstance(MetricsTrackerService.class);
    private static final String METRICS_DIR = ".cloneguard";
    private static final String METRICS_FILE = "metrics.jsonl";

    private final Project project;
    private final Gson gson = new Gson();

    // Current in-progress session state. Null fileName means no session
    // is currently open. Deliberately private with no getters — these
    // fields are only meaningful mid-session, and nothing outside this
    // class should be reading partial state.
    private String currentFileName;
    private int currentLocBefore;
    private int currentComplexityBefore;
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
    }

    public static MetricsTrackerService getInstance(Project project) {
        return project.getService(MetricsTrackerService.class);
    }

    /**
     * Called from ScanFileAction at the start of every manual scan.
     * Finalizes and persists the PREVIOUS session first (if it had any
     * refactor activity), then opens a fresh baseline for this new scan.
     */
    public synchronized void startSession(PsiFile psiFile) {
        finalizeCurrentSessionIfDirty();

        currentFileName = psiFile.getName();
        currentLocBefore = countLines(psiFile.getText());
        currentComplexityBefore = countCyclomaticComplexity(psiFile);
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
        session.complexityBefore = currentComplexityBefore;
        session.complexityAfter = currentComplexityBefore; // overwritten below if re-readable
        session.duplicatedLinesEliminated = currentDuplicatedLinesEliminated;
        session.extractCount = currentExtractCount;
        session.delegateCount = currentDelegateCount;
        session.pullUpCount = currentPullUpCount;
        session.pushDownCount = currentPushDownCount;
        session.type1Count = currentType1Count;
        session.type2Count = currentType2Count;
        session.type3Count = currentType3Count;
        session.type4Count = currentType4Count;

        // "After" LOC and complexity are the current on-disk state of the
        // same file, if it's still findable under the project. If the
        // file was renamed or deleted mid-session (unlikely, but
        // possible), fall back to the "before" values so the net-change
        // calculations read as zero rather than a misleading
        // negative/garbage number.
        Integer locAfter = tryReadCurrentLineCount(currentFileName);
        if (locAfter != null) {
            session.locAfter = locAfter;
        }
        Integer complexityAfter = tryReadCurrentComplexity(currentFileName);
        if (complexityAfter != null) {
            session.complexityAfter = complexityAfter;
        }

        persistSession(session);
        currentFileName = null;
    }

    private Integer tryReadCurrentLineCount(String fileName) {
        try {
            VirtualFile[] roots = ProjectRootManager.getInstance(project).getContentRoots();
            for (VirtualFile root : roots) {
                VirtualFile found = findByName(root, fileName);
                if (found != null) {
                    PsiFile psi = PsiManager.getInstance(project).findFile(found);
                    if (psi != null) return countLines(psi.getText());
                }
            }
        } catch (Exception e) {
            LOG.warn("CloneGuard: could not re-read file for metrics 'after' count: " + e.getMessage());
        }
        return null;
    }

    private Integer tryReadCurrentComplexity(String fileName) {
        try {
            VirtualFile[] roots = ProjectRootManager.getInstance(project).getContentRoots();
            for (VirtualFile root : roots) {
                VirtualFile found = findByName(root, fileName);
                if (found != null) {
                    PsiFile psi = PsiManager.getInstance(project).findFile(found);
                    if (psi != null) return countCyclomaticComplexity(psi);
                }
            }
        } catch (Exception e) {
            LOG.warn("CloneGuard: could not re-read file for metrics complexity 'after' count: " + e.getMessage());
        }
        return null;
    }

    private VirtualFile findByName(VirtualFile dir, String name) {
        if (!dir.isDirectory()) {
            return dir.getName().equals(name) ? dir : null;
        }
        for (VirtualFile child : dir.getChildren()) {
            VirtualFile result = findByName(child, name);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * Cyclomatic complexity, summed across every method in the file:
     * 1 (base path) per method, plus one for every decision point —
     * if, for, foreach, while, do-while, catch, ternary (?:), each
     * non-default switch case, and each && / || (every additional
     * operand beyond the first adds one more branch). Computed via
     * real PSI traversal rather than a text/regex heuristic, since
     * unlike line counting, complexity genuinely depends on real
     * control-flow structure -- exactly the kind of thing PSI exists
     * to provide reliably, and the same reason Scenario 2's Java-side
     * detection is more trustworthy than Scenario 3's regex-based
     * Python extraction elsewhere in this codebase.
     */
    private int countCyclomaticComplexity(PsiFile psiFile) {
        int total = 0;
        for (PsiMethod method : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
            PsiCodeBlock body = method.getBody();
            if (body == null) continue; // abstract/interface method — nothing to measure

            total += 1; // base complexity for the method's single straight-line path
            total += PsiTreeUtil.findChildrenOfType(body, PsiIfStatement.class).size();
            total += PsiTreeUtil.findChildrenOfType(body, PsiForStatement.class).size();
            total += PsiTreeUtil.findChildrenOfType(body, PsiForeachStatement.class).size();
            total += PsiTreeUtil.findChildrenOfType(body, PsiWhileStatement.class).size();
            total += PsiTreeUtil.findChildrenOfType(body, PsiDoWhileStatement.class).size();
            total += PsiTreeUtil.findChildrenOfType(body, PsiCatchSection.class).size();
            total += PsiTreeUtil.findChildrenOfType(body, PsiConditionalExpression.class).size(); // ternary

            for (PsiSwitchLabelStatement label : PsiTreeUtil.findChildrenOfType(body, PsiSwitchLabelStatement.class)) {
                if (!label.isDefaultCase()) total++;
            }

            for (PsiPolyadicExpression expr : PsiTreeUtil.findChildrenOfType(body, PsiPolyadicExpression.class)) {
                if (expr.getOperationTokenType() == JavaTokenType.ANDAND
                        || expr.getOperationTokenType() == JavaTokenType.OROR) {
                    // A chain like (a && b && c) has 3 operands but only
                    // 2 actual short-circuit decision points.
                    total += Math.max(0, expr.getOperands().length - 1);
                }
            }
        }
        return total;
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