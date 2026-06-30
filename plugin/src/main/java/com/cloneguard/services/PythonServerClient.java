package com.cloneguard.services;

import com.cloneguard.model.CloneGroup;
import com.cloneguard.model.CloneResult;
import com.cloneguard.model.CloneType;
import com.google.gson.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service(Service.Level.PROJECT)
public final class PythonServerClient {

    private static final Logger LOG = Logger.getInstance(PythonServerClient.class);
    private static final String BASE_URL = "http://localhost:8765";

    private HttpClient http;
    private final Gson gson = new Gson();

    public PythonServerClient() {}

    private HttpClient getHttp() {
        if (http == null) {
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
        }
        return http;
    }

    public boolean isServerAlive() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            HttpResponse<String> resp = getHttp().send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean resetIndex() {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("reset", true);
            payload.addProperty("code", "");
            HttpResponse<String> resp = post("/index", payload.toString());
            LOG.info("CloneGuard: index reset, status=" + resp.statusCode());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            LOG.debug("CloneGuard: resetIndex failed: " + e.getMessage());
            return false;
        }
    }

    public boolean indexFunction(String name, String body) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("code", body);
            payload.addProperty("name", name);
            HttpResponse<String> resp = post("/index", payload.toString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            LOG.debug("CloneGuard: index failed: " + e.getMessage());
            return false;
        }
    }

    public CloneResult check(String candidateCode) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("suggestion", candidateCode);
            HttpResponse<String> resp = post("/check", payload.toString());
            if (resp.statusCode() != 200) return CloneResult.noClone();
            return parseCloneResponse(resp.body(), candidateCode);
        } catch (Exception e) {
            LOG.debug("CloneGuard: check failed: " + e.getMessage());
            return CloneResult.noClone();
        }
    }

    // ── NEW: /scan endpoint for Scenario 2 file scan ──────────────────────────
    /**
     * Sends all functions to the /scan endpoint in one call.
     * Server handles Layer 1 (Type 1/2) and Layer 2 (Type 3/4) internally.
     * Returns a list of CloneGroups with correct pairings.
     */
    public List<CloneGroup> scanFile(Map<String, String> functions, String filename) {
        try {
            // Build the JSON payload: { filename, functions: [{name, code}, ...] }
            JsonObject payload = new JsonObject();
            payload.addProperty("filename", filename);

            JsonArray fnArray = new JsonArray();
            for (Map.Entry<String, String> entry : functions.entrySet()) {
                // Extract just the simple method name for display
                // PSI signature is "methodName(params):returnType" — we want "methodName"
                String fullSig  = entry.getKey();
                String simpleName = fullSig.contains("(")
                        ? fullSig.substring(0, fullSig.indexOf("(")).trim()
                        : fullSig;

                JsonObject fn = new JsonObject();
                fn.addProperty("name", simpleName);
                fn.addProperty("code", entry.getValue());
                fnArray.add(fn);
            }
            payload.add("functions", fnArray);

            LOG.info("CloneGuard: sending " + functions.size() + " functions to /scan");
            HttpResponse<String> resp = post("/scan", payload.toString());
            if (resp.statusCode() != 200) {
                LOG.warn("CloneGuard: /scan returned status " + resp.statusCode());
                return Collections.emptyList();
            }

            return parseScanResponse(resp.body());

        } catch (Exception e) {
            LOG.warn("CloneGuard: scanFile failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parses the /scan response JSON into CloneGroup list.
     * Response format: { cloneGroups: [{cloneType, similarity, functionA, functionB, detectionLayer, ...}] }
     */
    private List<CloneGroup> parseScanResponse(String json) {
        List<CloneGroup> groups = new ArrayList<>();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonArray arr  = obj.has("cloneGroups") ? obj.getAsJsonArray("cloneGroups") : new JsonArray();

            for (JsonElement el : arr) {
                JsonObject g = el.getAsJsonObject();

                String cloneTypeStr  = g.has("cloneType")      ? g.get("cloneType").getAsString()      : "";
                String similarityStr = g.has("similarity")     ? g.get("similarity").getAsString()     : "0%";
                String functionA     = g.has("functionA")      ? g.get("functionA").getAsString()      : "";
                String functionB     = g.has("functionB")      ? g.get("functionB").getAsString()      : "";
                String layerStr      = g.has("detectionLayer") ? g.get("detectionLayer").getAsString() : "";
                String detail        = "Detected via " + layerStr;

                // Parse similarity
                double similarity = 0.0;
                try {
                    similarity = Double.parseDouble(similarityStr.replace("%", "")) / 100.0;
                } catch (Exception ignored) {}

                // Parse clone type
                CloneType type;
                if      (cloneTypeStr.contains("1") || cloneTypeStr.toLowerCase().contains("exact"))    type = CloneType.TYPE_1;
                else if (cloneTypeStr.contains("2") || cloneTypeStr.toLowerCase().contains("renamed"))  type = CloneType.TYPE_2;
                else if (cloneTypeStr.contains("3") || cloneTypeStr.toLowerCase().contains("near"))     type = CloneType.TYPE_3;
                else if (cloneTypeStr.contains("4") || cloneTypeStr.toLowerCase().contains("semantic")) type = CloneType.TYPE_4;
                else    type = CloneType.TYPE_3;

                if (!functionA.isEmpty() && !functionB.isEmpty()) {
                    groups.add(new CloneGroup(type, similarity, List.of(functionA, functionB), detail));
                    LOG.info("CloneGuard: parsed clone group: " + functionA + " <-> " + functionB + " (" + type + ")");
                }
            }

            LOG.info("CloneGuard: parsed " + groups.size() + " clone groups from /scan response");
        } catch (Exception e) {
            LOG.warn("CloneGuard: parseScanResponse failed: " + e.getMessage());
        }
        return groups;
    }

    // ── AI Detection ──────────────────────────────────────────────────────────

    public AiDetectionResult detectAI(String code) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("code", code);
            HttpResponse<String> resp = post("/detect-ai", payload.toString());
            if (resp.statusCode() != 200) return AiDetectionResult.unknown();
            return parseAiResponse(resp.body());
        } catch (Exception e) {
            LOG.debug("CloneGuard: detectAI failed: " + e.getMessage());
            return AiDetectionResult.unknown();
        }
    }

    public static class AiDetectionResult {
        public final boolean isAiGenerated;
        public final double  confidence;
        public final String  label;

        public AiDetectionResult(boolean isAiGenerated, double confidence, String label) {
            this.isAiGenerated = isAiGenerated;
            this.confidence    = confidence;
            this.label         = label;
        }

        public static AiDetectionResult unknown() {
            return new AiDetectionResult(false, 0.0, "Unknown");
        }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private AiDetectionResult parseAiResponse(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            boolean isAi      = obj.has("isAiGenerated") && obj.get("isAiGenerated").getAsBoolean();
            double  confidence = obj.has("confidence")   ? obj.get("confidence").getAsDouble()  : 0.0;
            String  label      = obj.has("label")        ? obj.get("label").getAsString()       : "Unknown";
            LOG.info("CloneGuard AI Detection: isAI=" + isAi + " confidence=" + confidence + " label=" + label);
            return new AiDetectionResult(isAi, confidence, label);
        } catch (Exception e) {
            LOG.warn("CloneGuard: parseAiResponse failed: " + e.getMessage());
            return AiDetectionResult.unknown();
        }
    }

    private CloneResult parseCloneResponse(String json, String candidateCode) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            boolean isClone = obj.has("isClone") && obj.get("isClone").getAsBoolean();
            if (!isClone) return CloneResult.noClone();

            String cloneTypeStr  = obj.has("cloneType")  ? obj.get("cloneType").getAsString()  : "";
            String similarityStr = obj.has("similarity") ? obj.get("similarity").getAsString() : "0%";
            String matchedFn     = obj.has("matchFunction") ? obj.get("matchFunction").getAsString() : "";

            double similarity = 0.0;
            try {
                similarity = Double.parseDouble(similarityStr.replace("%", "")) / 100.0;
            } catch (Exception ignored) {}

            CloneType type;
            if      (cloneTypeStr.contains("1") || cloneTypeStr.toLowerCase().contains("exact"))    type = CloneType.TYPE_1;
            else if (cloneTypeStr.contains("2") || cloneTypeStr.toLowerCase().contains("renamed"))  type = CloneType.TYPE_2;
            else if (cloneTypeStr.contains("3") || cloneTypeStr.toLowerCase().contains("near"))     type = CloneType.TYPE_3;
            else if (cloneTypeStr.contains("4") || cloneTypeStr.toLowerCase().contains("semantic")) type = CloneType.TYPE_4;
            else    type = CloneType.TYPE_3;

            String layer = (type == CloneType.TYPE_1 || type == CloneType.TYPE_2)
                    ? "Layer 1 (Local — normalized identifier hash)"
                    : "Layer 2 (Server — CodeBERT+FAISS)";

            return new CloneResult.Builder()
                    .isClone(true)
                    .cloneType(type)
                    .similarity(similarity)
                    .matchedFunction(matchedFn)
                    .layer(layer)
                    .suggestedCode(candidateCode)
                    .build();
        } catch (Exception e) {
            LOG.warn("CloneGuard: parseCloneResponse failed: " + e.getMessage());
            return CloneResult.noClone();
        }
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return getHttp().send(req, HttpResponse.BodyHandlers.ofString());
    }
}