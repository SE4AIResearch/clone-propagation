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

    // Total cyclomatic complexity across the file, before/after this
    // session. Previously computed via in-house PSI traversal;
    // now sourced from SciTools Understand (see
    // UnderstandMetricsService), the same "Cyclomatic" value used
    // throughout the CloneGuard paper's evaluation methodology, rather
    // than a custom unvalidated formula.
    public int complexityBefore;
    public int complexityAfter;

    // NEW: additional OO design metrics from Understand, not previously
    // tracked in-app at all -- WMC (weighted methods per class, summed
    // cyclomatic complexity across the class's methods), CBO (coupling
    // between objects, how many other classes this one references),
    // DIT (depth of inheritance tree), NOC (number of direct
    // subclasses). Each has a before/after pair, same philosophy as
    // locBefore/locAfter and complexityBefore/complexityAfter.
    public int wmcBefore;
    public int wmcAfter;
    public int cboBefore;
    public int cboAfter;
    public int ditBefore;
    public int ditAfter;
    public int nocBefore;
    public int nocAfter;

    // NEW: true only if Understand was actually reachable and returned
    // real data for BOTH the "before" and "after" snapshot of this
    // session. If false, every *Before/*After field above other than
    // loc/duplicatedLines/refactor counts is meaningless (left at 0,
    // not a genuine "zero complexity" result) -- the dashboard must
    // check this before displaying any Understand-derived number, so a
    // missing-tool state never gets confused with an actually-clean
    // file.
    public boolean understandAvailable;

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

    /** Positive means WMC dropped (methods got individually simpler). */
    public int netWmcChanged() {
        return wmcBefore - wmcAfter;
    }

    /** Positive means coupling dropped (file depends on fewer other classes). */
    public int netCboChanged() {
        return cboBefore - cboAfter;
    }
}