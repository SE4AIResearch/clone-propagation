package com.cloneguard.model;

/**
 * One finalized data point for the Trend Dashboard: the aggregate
 * before/after result of a single scan session (from one manual "Scan
 * Current File" action up to the next one), covering every refactor the
 * user actually applied during that session.
 *
 * A session with zero refactors applied never becomes a RefactorSession
 * at all — see MetricsTrackerService.finalizeCurrentSessionIfDirty() —
 * so every instance of this class represents real, applied change, not
 * just a scan that found nothing or where nothing was acted on.
 *
 * Plain public fields, no-arg constructor: this is a Gson serialization
 * target (see MetricsTrackerService), the same JSON approach already
 * used elsewhere in this codebase (PythonServerClient.java).
 */
public class RefactorSession {

    public long timestamp;
    public String fileName;
    public int locBefore;
    public int locAfter;
    public int duplicatedLinesEliminated;
    public int extractCount;
    public int delegateCount;
    public int pullUpCount;
    public int pushDownCount;

    // EXTENDED: which CLONE TYPE (Type 1-4) each applied refactor was
    // originally fixing, separate from which TECHNIQUE was used to fix
    // it (extractCount etc. above) -- a Type 4 clone could be fixed by
    // Delegate, a Type 1 clone could be fixed by Extract OR Pull Up, so
    // these two breakdowns genuinely answer different questions and
    // aren't derivable from each other. Push Down has no associated
    // clone type at all (it isn't a duplication fix), so it never
    // increments any of these four.
    public int type1Count;
    public int type2Count;
    public int type3Count;
    public int type4Count;

    // EXTENDED: total cyclomatic complexity summed across every method
    // in the file, same before/after philosophy as locBefore/locAfter.
    // Computed via real PSI traversal (see MetricsTrackerService),
    // not a text heuristic -- unlike LOC, complexity genuinely needs to
    // know real control-flow structure (if/for/while/case/&&/||/ternary),
    // which only PSI can reliably provide.
    public int complexityBefore;
    public int complexityAfter;

    public RefactorSession() {
        // Required by Gson for deserialization.
    }

    public int totalRefactors() {
        return extractCount + delegateCount + pullUpCount + pushDownCount;
    }

    /** Positive means the file got shorter overall across this session. */
    public int netLinesChanged() {
        return locBefore - locAfter;
    }

    /** Positive means the file's total cyclomatic complexity dropped. */
    public int netComplexityChanged() {
        return complexityBefore - complexityAfter;
    }
}