package com.cloneguard.detection;

import com.cloneguard.model.CloneResult;
import com.cloneguard.model.CloneType;

import java.util.*;
import java.util.regex.*;

/**
* Layer 1 — Local Clone Detector
* Type 1: Exact match via Karp-Rabin hash on whitespace-normalized code
* Type 2: Match after replacing all identifiers with VAR/FUNC placeholders
*/
public class LocalCloneDetector {

    private final Map<String, Long> exactHashes      = new LinkedHashMap<>();
    private final Map<String, Long> normalizedHashes = new LinkedHashMap<>();
    private final Map<String, String> normalizedTexts = new LinkedHashMap<>();
    private final Map<String, String> bodyOnlyNormalizedTexts = new LinkedHashMap<>();

    public void indexFunction(String functionName, String body) {
        // FIX: indexers in this codebase don't agree on whether they pass
        // full "signature { body }" text or just the "{ body }" block —
        // InlineSuggestionListener's primary path indexes full method.getText(),
        // its PSI-exception fallback (via FileScannerService.extractFunctions)
        // indexes body-only. Previously this meant exact/normalized hashes
        // were computed over whichever shape happened to come in, so a
        // function indexed body-only could never hash-match a candidate that
        // always arrives as full signature+body (every check() candidate is
        // full text from the editor). Normalizing on the extracted body block
        // FIRST, for every code path, makes hash comparisons immune to which
        // shape the caller happened to pass in.
        String bodyBlock = extractBody(body);

        String normWS   = normalizeWhitespace(bodyBlock);
        String normId   = normalizeIdentifiers(bodyBlock);
        String bodyOnly = normalizeWhitespace(stripFunctionName(bodyBlock, functionName));
        exactHashes.put(functionName,      karpRabinHash(normWS));
        normalizedHashes.put(functionName, karpRabinHash(normId));
        normalizedTexts.put(functionName,  normId);
        bodyOnlyNormalizedTexts.put(functionName, bodyOnly);
        sourceBodies.put(functionName, bodyBlock);
    }

    public CloneResult check(String candidateCode) {
        return check(candidateCode, extractFunctionName(candidateCode));
    }

    /**
     * Best-effort extraction of the function name from a full method
     * signature, e.g. "public int sumArrayExact(int[] arr) {" -> "sumArrayExact".
     * Used by check(String) so callers passing full signature+body code don't
     * need to separately track and pass the function name.
     */
    private static String extractFunctionName(String code) {
        Matcher m = Pattern.compile(
            "(?:public|private|protected|static|\\s)+[\\w<>\\[\\],\\s]+?\\s(\\w+)\\s*\\([^)]*\\)\\s*\\{"
        ).matcher(code);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Extracts just the "{ ... }" body block from either a full
     * "signature { body }" string or an already-body-only "{ body }" string.
     * Making every comparison go through this first is what keeps Layer 1
     * correct regardless of which shape a given caller passes in.
     */
    private static String extractBody(String code) {
        if (code == null) return "";
        code = code.trim();
        if (code.startsWith("{")) return code;
        int idx = code.indexOf('{');
        return (idx != -1) ? code.substring(idx).trim() : code;
    }

    public CloneResult check(String candidateCode, String candidateFunctionName) {
        if (candidateCode == null || candidateCode.isBlank()) return CloneResult.noClone();

        String candidateBody = extractBody(candidateCode);
        String normWS  = normalizeWhitespace(candidateBody);
        String normId  = normalizeIdentifiers(candidateBody);
        long exactHash = karpRabinHash(normWS);
        long normHash  = karpRabinHash(normId);
        String candidateBodyOnly = normalizeWhitespace(stripFunctionName(candidateBody, candidateFunctionName));

        // Type 1: exact match (whitespace differences only, same everything else)
        for (Map.Entry<String, Long> e : exactHashes.entrySet()) {
            if (e.getValue().equals(exactHash)) {
                return new CloneResult.Builder()
                        .isClone(true).cloneType(CloneType.TYPE_1).similarity(1.0)
                        .matchedFunction(e.getKey())
                        .layer("Layer 1 (Local — exact hash)")
                        .suggestedCode(candidateCode).build();
            }
        }

        // FIX: Type 1 (name-only difference). If, after stripping just the
        // function name (not other identifiers) from both snippets, the bodies
        // are byte-identical, this is the same code with a different name —
        // i.e. Type 1, not Type 2.
        for (Map.Entry<String, String> e : bodyOnlyNormalizedTexts.entrySet()) {
            if (!e.getKey().equals(candidateFunctionName) && e.getValue().equals(candidateBodyOnly)) {
                return new CloneResult.Builder()
                        .isClone(true).cloneType(CloneType.TYPE_1).similarity(1.0)
                        .matchedFunction(e.getKey())
                        .layer("Layer 1 (Local — identical body, renamed function)")
                        .suggestedCode(candidateCode).build();
            }
        }

        // Type 2: normalized match (hash)
        for (Map.Entry<String, Long> e : normalizedHashes.entrySet()) {
            if (e.getValue().equals(normHash)) {
                if (!sameControlFlowShape(candidateBody, lookupSourceBody(e.getKey()))) {
                    continue; // different shape (e.g. loop vs recursion) -> not Type 2
                }
                return new CloneResult.Builder()
                        .isClone(true).cloneType(CloneType.TYPE_2).similarity(0.95)
                        .matchedFunction(e.getKey())
                        .layer("Layer 1 (Local — normalized identifier hash)")
                        .suggestedCode(candidateCode).build();
            }
        }

        // Type 2 fallback: token-by-token comparison (catches cases where hash differs but structure matches)
        for (Map.Entry<String, String> e : normalizedTexts.entrySet()) {
            int candidateTokenCount = tokenize(normId).size();
            int matchedTokenCount   = tokenize(e.getValue()).size();
            if (candidateTokenCount < 8 || matchedTokenCount < 8) {
                continue; // too short for fuzzy token similarity to be meaningful
            }
            double sim = tokenSimilarity(normId, e.getValue());
            if (sim >= 0.90) {
                if (!sameControlFlowShape(candidateBody, lookupSourceBody(e.getKey()))) {
                    continue;
                }
                return new CloneResult.Builder()
                        .isClone(true).cloneType(CloneType.TYPE_2).similarity(sim)
                        .matchedFunction(e.getKey())
                        .layer("Layer 1 (Local — token similarity " + String.format("%.0f%%", sim*100) + ")")
                        .suggestedCode(candidateCode).build();
            }
        }

        return CloneResult.noClone();
    }

    public boolean isEmpty()    { return exactHashes.isEmpty(); }
    public int indexedCount()   { return exactHashes.size(); }

    // ─── Shape comparison ──────────────────────────────────────────────────────────

    private final Map<String, String> sourceBodies = new LinkedHashMap<>();

    private String lookupSourceBody(String functionName) {
        return sourceBodies.getOrDefault(functionName, "");
    }

    /**
     * Two snippets have the "same shape" if they agree on whether they
     * contain a loop (for/while). Mirrors the shape check used server-side
     * (Layer 2) so Type 2 vs Type 4 classification is consistent between the
     * local and server detectors.
     */
    private static boolean sameControlFlowShape(String a, String b) {
        boolean loopA = a.matches("(?s).*\\b(for|while)\\b.*");
        boolean loopB = b.matches("(?s).*\\b(for|while)\\b.*");
        return loopA == loopB;
    }

    private static String stripFunctionName(String code, String functionName) {
        if (functionName == null || functionName.isBlank()) return code;
        return code.replaceFirst("\\b" + Pattern.quote(functionName) + "\\s*\\(", "FUNC_NAME(");
    }

    // ─── Hashing ─────────────────────────────────────────────────────────────────

    static long karpRabinHash(String text) {
        final long BASE = 31L, MOD = 1_000_000_007L;
        long hash = 0, power = 1;
        for (char c : text.toCharArray()) {
            hash  = (hash + c * power) % MOD;
            power = (power * BASE) % MOD;
        }
        return hash;
    }

    // ─── Normalization ────────────────────────────────────────────────────────────

    static String normalizeWhitespace(String code) {
        return code.replaceAll("\\s+", " ").trim();
    }

    static String normalizeIdentifiers(String code) {
        String s = code
                .replaceAll("//[^\n]*", "")
                .replaceAll("/\\*[\\s\\S]*?\\*/", "");

        StringBuilder sb = new StringBuilder();
        Pattern p = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[^A-Za-z0-9_\\s]+|\\s+");
        Matcher m = p.matcher(s);

        while (m.find()) {
            String token = m.group();

            if (token.matches("\\s+")) {
                sb.append(" ");
                continue;
            }

            if (isKeywordOrType(token)) {
                sb.append(token);
            } else if (token.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                int end = m.end();
                String rest = s.substring(end).stripLeading();
                if (rest.startsWith("(")) {
                    sb.append("FUNC");
                } else {
                    sb.append("VAR");
                }
            } else {
                sb.append(token);
            }
        }

        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    static double tokenSimilarity(String a, String b) {
        List<String> tokensA = tokenize(a);
        List<String> tokensB = tokenize(b);

        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0;

        Map<String, Integer> freqA = new HashMap<>();
        for (String t : tokensA) freqA.merge(t, 1, Integer::sum);

        int matches = 0;
        for (String t : tokensB) {
            int count = freqA.getOrDefault(t, 0);
            if (count > 0) {
                matches++;
                freqA.put(t, count - 1);
            }
        }

        return (2.0 * matches) / (tokensA.size() + tokensB.size());
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[^\\s]+").matcher(text);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    private static final Set<String> KEYWORDS = Set.of(
        "abstract","assert","boolean","break","byte","case","catch","char","class",
        "const","continue","default","do","double","else","enum","extends","final",
        "finally","float","for","goto","if","implements","import","instanceof","int",
        "interface","long","native","new","package","private","protected","public",
        "return","short","static","strictfp","super","switch","synchronized","this",
        "throw","throws","transient","try","void","volatile","while","true","false","null",
        "String","List","Map","Set","ArrayList","HashMap","Optional","Integer","Double",
        "Boolean","Object","Arrays","System","Math"
    );

    private static boolean isKeywordOrType(String token) {
        return KEYWORDS.contains(token);
    }
}
