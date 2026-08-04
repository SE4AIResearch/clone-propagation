package com.cloneguard.services;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shells out to the SciTools Understand command-line tool (`und`) to
 * compute CC, WMC, LOC, CBO, DIT, and NOC for a given file, replacing
 * the previous in-house PSI-based cyclomatic complexity calculation.
 *
 * Requires:
 *   - Understand installed and licensed on the user's machine
 *   - `und` available on the system PATH
 * If either is missing, methods here return null and the caller should
 * fall back to showing "Understand not available" rather than crashing.
 */
@Service(Service.Level.PROJECT)
public final class UnderstandMetricsService {

    private static final Logger LOG = Logger.getInstance(UnderstandMetricsService.class);

    public static class UnderstandMetrics {
        public int cyclomaticComplexity;   // CC -- actually MaxCyclomatic (worst single method), since raw per-class Cyclomatic doesn't exist in Understand
        public int weightedMethodsPerClass; // WMC (SumCyclomatic)
        public int linesOfCode;            // LOC (CountLineCode)
        public int couplingBetweenObjects; // CBO (CountClassCoupled)
        public int depthOfInheritance;     // DIT (MaxInheritanceTree)
        public int numberOfChildren;       // NOC (CountClassDerived)
    }

    private final Project project;

    public UnderstandMetricsService(Project project) {
        this.project = project;
    }

    /**
     * Checks whether `und` is actually reachable on this machine before
     * attempting any real analysis. Callers should check this first and
     * show a clear message if false, rather than silently failing later.
     */
    // FIX: GUI apps on macOS (IntelliJ included) do NOT inherit the
    // PATH set up in a user's shell config (.zshrc, .bash_profile, etc.)
    // -- that PATH only applies to processes launched FROM a terminal.
    // Confirmed live: `und` worked perfectly from Terminal (added to
    // PATH there) but the plugin still reported "Understand not
    // available", because IntelliJ itself never saw that PATH change at
    // all. Rather than rely on bare "und" resolving via PATH, this
    // checks a list of known install locations directly (Understand's
    // default macOS install path first, then falls back to a bare "und"
    // in case it genuinely is on PATH for some other reason -- e.g. the
    // IDE was launched from a terminal with `idea .`).
    private static final String[] UND_CANDIDATE_PATHS = {
            "/Applications/Understand.app/Contents/MacOS/und",
            "und" // fallback: bare command, relies on PATH being inherited
    };

    private String resolvedUndPath;

    public boolean isUnderstandAvailable() {
        if (resolvedUndPath != null) return true;
        for (String candidate : UND_CANDIDATE_PATHS) {
            try {
                Process check = new ProcessBuilder(candidate, "version")
                        .redirectErrorStream(true)
                        .start();
                boolean finished = check.waitFor(5, TimeUnit.SECONDS);
                if (finished && check.exitValue() == 0) {
                    resolvedUndPath = candidate;
                    return true;
                }
            } catch (IOException | InterruptedException e) {
                // Try the next candidate path.
            }
        }
        LOG.info("Understand (`und`) not found at any known location or on PATH.");
        return false;
    }

    /**
     * FIX (professor-flagged, real UX concern -- confirmed valid):
     * previously created a brand-new temporary database from scratch on
     * EVERY single call -- create + add + analyze + settings + metrics,
     * five full `und` subprocess launches every time, no matter how
     * many times this ran against the same project. That's the actual
     * source of the 8-10 second wait: `create` and `settings` are
     * one-time setup work that was being redone every call for no
     * reason. Switched to a persistent database stored at
     * .cloneguard/und-db/analysis.und inside the project (created and
     * configured ONCE, the first time Understand analysis ever runs for
     * this project), reused on every subsequent call -- only add,
     * analyze, and metrics run after that first time, which is the
     * genuinely necessary work (the file's current content still needs
     * to be re-registered and re-analyzed every time, since that's what
     * actually changed), cutting two full subprocess launches off every
     * call after the first.
     */
    private Path getPersistentDbPath() throws IOException {
        String basePath = project.getBasePath();
        if (basePath == null) {
            // No real project root to persist into (e.g. a default/
            // template project) -- fall back to a one-off temp database
            // rather than fail outright. Slower, but still correct.
            Path fallback = Files.createTempFile("cloneguard-und-", ".und");
            Files.deleteIfExists(fallback);
            return fallback;
        }
        Path dbDir = Paths.get(basePath, ".cloneguard", "und-db");
        Files.createDirectories(dbDir);
        return dbDir.resolve("analysis.und");
    }

    /**
     * Runs analysis for a single file against the persistent, reused
     * project database (see getPersistentDbPath() above), and parses
     * the resulting metrics for that file's class(es). Scoped to one
     * file being ADDED/ANALYZED at a time (rather than the whole
     * project every call) specifically to keep this usable during an
     * interactive scan -- a full-project Understand re-analysis on
     * every paste or scan action would still be far too slow, even with
     * the persistent-database fix.
     *
     * Returns null if `und` isn't available or analysis fails; callers
     * must handle that case explicitly rather than assume success.
     */
    public UnderstandMetrics analyzeFile(String absoluteFilePath) {
        if (!isUnderstandAvailable()) {
            return null;
        }

        try {
            Path db = getPersistentDbPath();
            String dbPath = db.toString();
            boolean isFirstRunForThisProject = !Files.exists(db);

            if (isFirstRunForThisProject) {
                runUnd("create", "-db", dbPath, "-languages", "java");
                // FIX (found live, this session): "Cyclomatic" is a
                // per-METHOD-only metric in Understand -- it is
                // legitimately BLANK on class-level rows, confirmed
                // directly against real CSV output earlier this session
                // (LocalCloneDetector's class row had Cyclomatic blank,
                // SumCyclomatic=46). Reading it directly for a
                // class-level "CC" figure silently produced 0 every
                // time, not a genuine zero-complexity result.
                // MaxCyclomatic (the single most complex method in the
                // class) IS a valid class-level aggregate, same as
                // SumCyclomatic -- added here and used instead, below.
                // This settings call only needs to run ONCE per
                // database, the same as create -- it's a database-level
                // configuration, not something that needs redoing per
                // file.
                runUnd("settings", "-db", dbPath, "-MetricsMetricsAdd",
                        "Cyclomatic", "MaxCyclomatic", "SumCyclomatic", "CountClassCoupled",
                        "MaxInheritanceTree", "CountClassDerived", "CountLineCode");
            }

            runUnd("add", "-db", dbPath, absoluteFilePath);
            runUnd("analyze", "-all", "-db", dbPath);

            Path csvOut = Files.createTempFile("cloneguard-und-metrics-", ".csv");
            try {
                runUnd("metrics", "-all", "-db", dbPath, csvOut.toString());
                return parseCsvForFile(csvOut, absoluteFilePath);
            } finally {
                try {
                    Files.deleteIfExists(csvOut);
                } catch (IOException ignored) {
                    // Best-effort cleanup; a leftover temp CSV isn't harmful.
                }
            }

        } catch (IOException | InterruptedException e) {
            LOG.warn("Understand analysis failed for " + absoluteFilePath, e);
            return null;
        }
    }

    private void runUnd(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(resolvedUndPath != null ? resolvedUndPath : "und");
        for (String a : args) {
            command.add(a);
        }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        // Drain output so the process doesn't block on a full pipe buffer.
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {
                // Intentionally discarded; `und` is verbose and we only
                // care about the final CSV, not this console output.
            }
        }

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("und command timed out: " + String.join(" ", command));
        }
    }

    /**
     * Reads the resulting CSV and pulls out the row whose File column
     * matches the analyzed file and whose Kind ends in "Class" (Public
     * Class, Private Static Class, etc.) -- the class-level metrics row,
     * not the file- or package-level rollup rows which don't carry
     * CBO/DIT/NOC.
     */
    private UnderstandMetrics parseCsvForFile(Path csvPath, String targetFilePath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath);
        if (lines.isEmpty()) {
            return null;
        }

        String[] headers = splitCsvLine(lines.get(0));
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            colIndex.put(headers[i], i);
        }

        // FIX (found live, Pull Up demo test): this used to return on the
        // FIRST matching class-kind row for the file and stop there. That
        // was correct for the common one-class-per-file case, but our
        // Pull Up/Push Down demo intentionally puts multiple classes in a
        // single file (Employee/Manager/Engineer) so CloneGuard's
        // single-file scanner can see the clone across them -- and
        // against a multi-class file, "first row" silently meant "always
        // the same one class, regardless of which class actually
        // changed," making before/after look identical even after a
        // real refactor. Aggregating across every class-row for this
        // file instead: MaxCyclomatic and MaxInheritanceTree take the
        // worst value found (still meaningful as file-level maxima);
        // SumCyclomatic, CountClassCoupled, and CountClassDerived are
        // summed, since those are naturally additive across classes and
        // summing SumCyclomatic in particular is what actually reflects
        // duplicate-method elimination (two classes each owning a copy,
        // reduced to one shared copy, is a real drop in the file's total).
        // Note: for a Pull Up specifically, DIT and NOC are expected to
        // legitimately stay unchanged -- moving an existing method
        // between existing classes doesn't alter inheritance depth or
        // child counts, so "before == after" there is a correct reading,
        // not a bug.
        boolean found = false;
        int maxCyclomatic = 0;
        int sumCyclomatic = 0;
        int totalLoc = 0;
        int sumCoupled = 0;
        int maxInheritance = 0;
        int sumDerived = 0;

        for (int i = 1; i < lines.size(); i++) {
            String[] row = splitCsvLine(lines.get(i));
            if (row.length <= colIndex.getOrDefault("Kind", -1)) continue;

            String kind = safeGet(row, colIndex, "Kind");
            String file = safeGet(row, colIndex, "File");

            if (kind == null || !kind.endsWith("Class")) continue;
            if (file == null || !new File(file).getAbsolutePath()
                    .equals(new File(targetFilePath).getAbsolutePath())) continue;

            found = true;
            maxCyclomatic = Math.max(maxCyclomatic, parseIntSafe(safeGet(row, colIndex, "MaxCyclomatic")));
            sumCyclomatic += parseIntSafe(safeGet(row, colIndex, "SumCyclomatic"));
            totalLoc += parseIntSafe(safeGet(row, colIndex, "CountLineCode"));
            sumCoupled += parseIntSafe(safeGet(row, colIndex, "CountClassCoupled"));
            maxInheritance = Math.max(maxInheritance, parseIntSafe(safeGet(row, colIndex, "MaxInheritanceTree")));
            sumDerived += parseIntSafe(safeGet(row, colIndex, "CountClassDerived"));
        }

        if (!found) return null; // No matching class-level row found for this file.

        UnderstandMetrics m = new UnderstandMetrics();
        m.cyclomaticComplexity = maxCyclomatic;
        m.weightedMethodsPerClass = sumCyclomatic;
        m.linesOfCode = totalLoc;
        m.couplingBetweenObjects = sumCoupled;
        m.depthOfInheritance = maxInheritance;
        m.numberOfChildren = sumDerived;
        return m;
    }

    private String safeGet(String[] row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null || idx >= row.length) return null;
        return row[idx];
    }

    private int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Minimal CSV splitter handling quoted fields with embedded commas,
     * since Understand's output quotes entity names that may contain
     * commas (e.g. method signatures).
     */
    private String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}