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

    public void indexFunction(String functionName, String body) {
        String normWS   = normalizeWhitespace(body);
        String normId   = normalizeIdentifiers(body);
        exactHashes.put(functionName,      karpRabinHash(normWS));
        normalizedHashes.put(functionName, karpRabinHash(normId));
        normalizedTexts.put(functionName,  normId);
    }

    public CloneResult check(String candidateCode) {
        if (candidateCode == null || candidateCode.isBlank()) return CloneResult.noClone();

        String normWS  = normalizeWhitespace(candidateCode);
        String normId  = normalizeIdentifiers(candidateCode);
        long exactHash = karpRabinHash(normWS);
        long normHash  = karpRabinHash(normId);

        // Type 1: exact match
        for (Map.Entry<String, Long> e : exactHashes.entrySet()) {
            if (e.getValue().equals(exactHash)) {
                return new CloneResult.Builder()
                        .isClone(true).cloneType(CloneType.TYPE_1).similarity(1.0)
                        .matchedFunction(e.getKey())
                        .layer("Layer 1 (Local — exact hash)")
                        .suggestedCode(candidateCode).build();
            }
        }

        // Type 2: normalized match (hash)
        for (Map.Entry<String, Long> e : normalizedHashes.entrySet()) {
            if (e.getValue().equals(normHash)) {
                return new CloneResult.Builder()
                        .isClone(true).cloneType(CloneType.TYPE_2).similarity(0.95)
                        .matchedFunction(e.getKey())
                        .layer("Layer 1 (Local — normalized identifier hash)")
                        .suggestedCode(candidateCode).build();
            }
        }

        // Type 2 fallback: token-by-token comparison (catches cases where hash differs but structure matches)
        for (Map.Entry<String, String> e : normalizedTexts.entrySet()) {
            double sim = tokenSimilarity(normId, e.getValue());
            if (sim >= 0.90) {
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

    /**
     * Replace all user-defined identifiers with VAR or FUNC.
     * Preserves Java keywords and primitive types so structure is kept.
     */
    static String normalizeIdentifiers(String code) {
        // Strip comments
        String s = code
                .replaceAll("//[^\n]*", "")
                .replaceAll("/\\*[\\s\\S]*?\\*/", "");

        // Tokenize: split into identifier tokens and non-identifier tokens
        StringBuilder sb = new StringBuilder();
        Pattern p = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[^A-Za-z0-9_\\s]+|\\s+");
        Matcher m = p.matcher(s);

        String prev = "";
        while (m.find()) {
            String token = m.group();

            if (token.matches("\\s+")) {
                sb.append(" ");
                continue;
            }

            if (isKeywordOrType(token)) {
                sb.append(token);
            } else if (token.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                // Look ahead in remaining string for '('
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
            prev = token;
        }

        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * Token-by-token similarity between two normalized strings.
     * Counts matching tokens / total tokens.
     */
    static double tokenSimilarity(String a, String b) {
        List<String> tokensA = tokenize(a);
        List<String> tokensB = tokenize(b);

        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0;

        // Count matching tokens using multiset intersection
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
