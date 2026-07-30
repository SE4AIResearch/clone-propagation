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
        public int cyclomaticComplexity;   // CC
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
    public boolean isUnderstandAvailable() {
        try {
            Process check = new ProcessBuilder("und", "version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = check.waitFor(5, TimeUnit.SECONDS);
            return finished && check.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            LOG.info("Understand (`und`) not found on PATH: " + e.getMessage());
            return false;
        }
    }

    /**
     * Runs a full create/add/analyze/metrics cycle scoped to a single
     * file, in a temporary database, and parses the resulting metrics
     * for that file's class(es). Scoped to one file (rather than the
     * whole project) specifically to keep this usable during an
     * interactive scan -- a full-project Understand analysis would be
     * far too slow to run on every paste or scan action.
     *
     * Returns null if `und` isn't available or analysis fails; callers
     * must handle that case explicitly rather than assume success.
     */
    public UnderstandMetrics analyzeFile(String absoluteFilePath) {
        if (!isUnderstandAvailable()) {
            return null;
        }

        Path tempDb = null;
        try {
            tempDb = Files.createTempFile("cloneguard-und-", ".und");
            Files.deleteIfExists(tempDb); // und create expects the path not to already exist
            String dbPath = tempDb.toString();

            runUnd("create", "-db", dbPath, "-languages", "java");
            runUnd("add", "-db", dbPath, absoluteFilePath);
            runUnd("analyze", "-all", "-db", dbPath);
            runUnd("settings", "-db", dbPath, "-MetricsMetricsAdd",
                    "Cyclomatic", "SumCyclomatic", "CountClassCoupled",
                    "MaxInheritanceTree", "CountClassDerived", "CountLineCode");

            Path csvOut = Files.createTempFile("cloneguard-und-metrics-", ".csv");
            runUnd("metrics", "-all", "-db", dbPath, csvOut.toString());

            return parseCsvForFile(csvOut, absoluteFilePath);

        } catch (IOException | InterruptedException e) {
            LOG.warn("Understand analysis failed for " + absoluteFilePath, e);
            return null;
        } finally {
            if (tempDb != null) {
                try {
                    Files.deleteIfExists(tempDb);
                } catch (IOException ignored) {
                    // Best-effort cleanup; a leftover temp db isn't harmful.
                }
            }
        }
    }

    private void runUnd(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("und");
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

        for (int i = 1; i < lines.size(); i++) {
            String[] row = splitCsvLine(lines.get(i));
            if (row.length <= colIndex.getOrDefault("Kind", -1)) continue;

            String kind = safeGet(row, colIndex, "Kind");
            String file = safeGet(row, colIndex, "File");

            if (kind == null || !kind.endsWith("Class")) continue;
            if (file == null || !new File(file).getAbsolutePath()
                    .equals(new File(targetFilePath).getAbsolutePath())) continue;

            UnderstandMetrics m = new UnderstandMetrics();
            m.cyclomaticComplexity = parseIntSafe(safeGet(row, colIndex, "Cyclomatic"));
            m.weightedMethodsPerClass = parseIntSafe(safeGet(row, colIndex, "SumCyclomatic"));
            m.linesOfCode = parseIntSafe(safeGet(row, colIndex, "CountLineCode"));
            m.couplingBetweenObjects = parseIntSafe(safeGet(row, colIndex, "CountClassCoupled"));
            m.depthOfInheritance = parseIntSafe(safeGet(row, colIndex, "MaxInheritanceTree"));
            m.numberOfChildren = parseIntSafe(safeGet(row, colIndex, "CountClassDerived"));
            return m;
        }
        return null; // No matching class-level row found for this file.
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