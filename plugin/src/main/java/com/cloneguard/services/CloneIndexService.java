package com.cloneguard.services;

import com.cloneguard.detection.LocalCloneDetector;
import com.cloneguard.model.CloneResult;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

@Service(Service.Level.APP)
public final class CloneIndexService {

    private LocalCloneDetector localDetector = new LocalCloneDetector();
    private final Map<String, String> functionBodies = new LinkedHashMap<>();

    public static CloneIndexService getInstance() {
        return ApplicationManager.getApplication().getService(CloneIndexService.class);
    }

    public void indexFunction(Project project, String name, String body) {
        localDetector.indexFunction(name, body);
        functionBodies.put(name, body);
        PythonServerClient client = project.getService(PythonServerClient.class);
        if (client != null && client.isServerAlive()) {
            client.indexFunction(name, body);
        }
    }

    public void clear() {
        localDetector = new LocalCloneDetector();
        functionBodies.clear();
    }

    public void clearWithServer(Project project) {
        localDetector = new LocalCloneDetector();
        functionBodies.clear();
        PythonServerClient client = project.getService(PythonServerClient.class);
        if (client != null && client.isServerAlive()) {
            client.resetIndex();
        }
    }

    // ── Rule-Based AI Detection (runs locally, no server needed) ──────────────

    /**
     * Detects if code is AI-generated using 4 rule-based signals.
     *
     * Signal 1: Average identifier length > 8 chars
     *           AI uses: calculateTotalSum, processUserInput
     *           Human uses: calc, tmp, s, i
     *
     * Signal 2: Perfect camelCase 100% of identifiers
     *           AI always follows conventions perfectly
     *           Humans mix styles, use abbreviations
     *
     * Signal 3: Has descriptive comment (// or /*)
     *           AI always adds comments explaining code
     *           Humans rarely comment short functions
     *
     * Signal 4: Uses enhanced for-each loop or descriptive loop variables
     *           AI: for (int number : numbers)
     *           Human: for (int i=0;i<arr.length;i++)
     *
     * 3/4 signals → High confidence AI
     * 2/4 signals → Medium confidence AI
     * 1/4 signals → Low confidence, likely human
     * 0/4 signals → Human written
     */
    public AiDetectionResult detectAiLocal(String code) {
        if (code == null || code.trim().length() < 20) {
            return new AiDetectionResult(false, 0.0, "Too short");
        }

        // ── KEY FIX: Extract body only for analysis ────────────────────────
        // Professor's feedback: developer may write their own method name
        // but copy the body from AI. So we analyze ONLY the body — the code
        // between { and } — ignoring method name and parameters entirely.
        String bodyOnly = extractBodyOnly(code);
        if (bodyOnly.length() < 10) {
            return new AiDetectionResult(false, 0.0, "Body too short");
        }

        int signals = 0;

        // Signal 1: Average identifier length in BODY only
        // Excludes method name and parameter names from signature
        double avgIdLength = getAverageIdentifierLength(bodyOnly);
        if (avgIdLength > 7.5) {
            signals++;
        }

        // Signal 2: Perfect camelCase ratio in BODY only
        double camelCaseRatio = getCamelCaseRatio(bodyOnly);
        if (camelCaseRatio > 0.85) {
            signals++;
        }

        // Signal 3: Has comment in BODY only
        // Comments inside the method body — not the signature
        boolean hasComment = bodyOnly.contains("//") || bodyOnly.contains("/*");
        if (hasComment) {
            signals++;
        }

        // Signal 4: Enhanced for loop or descriptive loop variable in BODY only
        boolean hasEnhancedFor = Pattern.compile("for\\s*\\(\\s*\\w+\\s+\\w{4,}\\s*:\\s*\\w+").matcher(bodyOnly).find();
        boolean hasDescriptiveLoop = Pattern.compile("for\\s*\\([^)]*\\b[a-z][a-zA-Z]{4,}\\b").matcher(bodyOnly).find();
        if (hasEnhancedFor || hasDescriptiveLoop) {
            signals++;
        }

        // Signal 5: Method chaining — AI almost always chains methods
        // e.g. new StringBuilder(x).reverse().toString()
        // Humans break into separate lines
        boolean hasMethodChaining = Pattern.compile("\\.\\w+\\(.*?\\)\\.\\w+\\(").matcher(bodyOnly).find();
        if (hasMethodChaining) {
            signals++;
        }

        // Count lines in body
        int lineCount = bodyOnly.split("\n").length;
        boolean isShortSnippet = lineCount <= 8;

        // Calculate confidence
        double confidence;
        boolean isAi;
        String label;

        // For short snippets need 2 signals minimum
        int threshold = isShortSnippet ? 2 : 3;

        if (signals >= threshold + 1) {
            confidence = 0.85;
            isAi = true;
            label = "AI Generated — High Confidence";
        } else if (signals >= threshold) {
            confidence = 0.60;
            isAi = true;
            label = "Possibly AI Generated";
        } else {
            confidence = 0.10;
            isAi = false;
            label = "Human Written";
        }

        return new AiDetectionResult(isAi, confidence, label);
    }

    /**
     * Extracts only the method body — code between first { and last }
     * This ignores method name, return type, and parameters
     * so AI detection is based purely on the code the developer copied
     */
    private String extractBodyOnly(String code) {
        if (code == null) return "";
        code = code.trim();
        int start = code.indexOf('{');
        int end = code.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return code.substring(start + 1, end).trim();
        }
        return code;
    }

    private double getAverageIdentifierLength(String code) {
        // Extract all identifiers (variable names, method names)
        Set<String> keywords = new HashSet<>(Arrays.asList(
            "public", "private", "protected", "static", "final", "void",
            "int", "long", "double", "float", "boolean", "char", "byte",
            "short", "class", "interface", "extends", "implements", "new",
            "return", "if", "else", "for", "while", "do", "switch", "case",
            "break", "continue", "try", "catch", "throw", "throws", "this",
            "super", "null", "true", "false", "String", "Object", "import",
            "package", "instanceof", "abstract", "synchronized"
        ));

        Pattern identPattern = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9]*\\b");
        Matcher m = identPattern.matcher(code);
        List<Integer> lengths = new ArrayList<>();
        while (m.find()) {
            String word = m.group();
            if (!keywords.contains(word) && word.length() > 1) {
                lengths.add(word.length());
            }
        }
        if (lengths.isEmpty()) return 0.0;
        return lengths.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private double getCamelCaseRatio(String code) {
        // Extract user-defined identifiers
        Set<String> keywords = new HashSet<>(Arrays.asList(
            "public", "private", "protected", "static", "final", "void",
            "int", "long", "double", "float", "boolean", "char", "String",
            "return", "if", "else", "for", "while", "new", "null", "true", "false"
        ));

        Pattern identPattern = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9]*\\b");
        Matcher m = identPattern.matcher(code);
        int total = 0;
        int camelCase = 0;

        while (m.find()) {
            String word = m.group();
            if (!keywords.contains(word) && word.length() > 2) {
                total++;
                // camelCase = starts lowercase, contains uppercase
                if (Character.isLowerCase(word.charAt(0)) &&
                    word.chars().anyMatch(Character::isUpperCase)) {
                    camelCase++;
                }
            }
        }
        if (total == 0) return 0.0;
        return (double) camelCase / total;
    }

    // ── AI Detection Result inner class ───────────────────────────────────────

    public static class AiDetectionResult {
        public final boolean isAiGenerated;
        public final double confidence;
        public final String label;

        public AiDetectionResult(boolean isAiGenerated, double confidence, String label) {
            this.isAiGenerated = isAiGenerated;
            this.confidence = confidence;
            this.label = label;
        }

        public static AiDetectionResult unknown() {
            return new AiDetectionResult(false, 0.0, "Unknown");
        }
    }

    // ── Main detection pipeline ────────────────────────────────────────────────

    public CloneResult detect(Project project, String candidateCode) {
        if (candidateCode == null || candidateCode.isBlank()) return CloneResult.noClone();

        // handleInsertion already combines developer-typed signature + pasted body.
        // Pass full code directly to Layer 1 — no stripping or wrapping.
        final String codeToAnalyze = candidateCode.trim();

        PythonServerClient client = project.getService(PythonServerClient.class);
        boolean serverAlive = client != null && client.isServerAlive();

        // ── Step 1: Rule-based AI detection on normalized code ────────────
        AiDetectionResult localAi = detectAiLocal(codeToAnalyze);

        // ── Step 2: Clone detection in background ─────────────────────────
        ExecutorService executor = Executors.newSingleThreadExecutor();
        final PythonServerClient finalClient = client;
        final boolean finalServerAlive = serverAlive;

        Future<CloneResult> cloneFuture = executor.submit(() -> {
            // ── Layer 1: run on FULL code (signature + body) ──────────────
            // Key fix: previously extractBody() stripped the signature before
            // Layer 1 ran, so body-only pastes where the developer typed their
            // own method name never matched via normalized hash (Type 2).
            // Now Layer 1 sees the full combined method and detects Type 2 correctly.
            CloneResult layer1 = localDetector.check(codeToAnalyze);
            if (layer1.isClone) return layer1;

            // ── Layer 2: server gets full code too ────────────────────────
            if (finalServerAlive) return finalClient.check(codeToAnalyze);
            return CloneResult.noClone();
        });

        CloneResult cloneResult = CloneResult.noClone();
        try {
            cloneResult = cloneFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // clone detection failed
        }
        executor.shutdown();

        // ── Step 3: Apply your exact rules ────────────────────────────────
        //
        // AI + Clone     → show full warning (AI badge + Clone badge)
        // AI + No Clone  → show AI warning only
        // Human + Clone  → show clone warning only (no AI mention)
        // Human + No Clone → nothing (return noClone, no popup)

        boolean isAi = localAi.isAiGenerated;
        boolean isClone = cloneResult.isClone;

        if (!isAi && !isClone) {
            // Human + No Clone → nothing
            return CloneResult.noClone();
        }

        return new CloneResult.Builder()
                .isClone(isClone)
                .cloneType(isClone ? cloneResult.cloneType : null)
                .similarity(isClone ? cloneResult.similarity : 0)
                .matchedFunction(isClone ? cloneResult.matchedFunction : "")
                .layer(isClone ? cloneResult.layer : "")
                .suggestedCode(candidateCode)
                .isAiGenerated(isAi)
                .aiConfidence(localAi.confidence)
                .aiLabel(localAi.label)
                .build();
    }

    public int indexedFunctionCount() { return functionBodies.size(); }

    public Collection<Map.Entry<String, String>> getAllFunctions() {
        return Collections.unmodifiableSet(functionBodies.entrySet());
    }

    private String extractBody(String code) {
        if (code == null) return "";
        code = code.trim();
        if (code.startsWith("{")) return code;
        int idx = code.indexOf('{');
        if (idx != -1) return code.substring(idx).trim();
        return code;
    }
}