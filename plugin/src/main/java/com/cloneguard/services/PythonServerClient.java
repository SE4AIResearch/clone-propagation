package com.cloneguard.services;

import com.cloneguard.model.CloneResult;
import com.cloneguard.model.CloneType;
import com.google.gson.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
            payload.addProperty("name", name);  // send name so server uses it directly
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

    /**
     * Calls /detect-ai endpoint to check if code is AI-generated.
     * Returns: [isAiGenerated, confidence, label]
     */
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

    // ── Inner class for AI detection result ───────────────────────────────────

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
            boolean isAi       = obj.has("isAiGenerated") && obj.get("isAiGenerated").getAsBoolean();
            double  confidence  = obj.has("confidence") ? obj.get("confidence").getAsDouble() : 0.0;
            String  label       = obj.has("label") ? obj.get("label").getAsString() : "Unknown";
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

            String cloneTypeStr = obj.has("cloneType") ? obj.get("cloneType").getAsString() : "";
            String similarityStr = obj.has("similarity") ? obj.get("similarity").getAsString() : "0%";
            String matchedFn    = obj.has("matchFunction") ? obj.get("matchFunction").getAsString() : "";

            double similarity = 0.0;
            try {
                similarity = Double.parseDouble(similarityStr.replace("%", "")) / 100.0;
            } catch (Exception ignored) {}

            CloneType type;
            if (cloneTypeStr.contains("1") || cloneTypeStr.toLowerCase().contains("exact")) {
                type = CloneType.TYPE_1;
            } else if (cloneTypeStr.contains("2") || cloneTypeStr.toLowerCase().contains("renamed")) {
                type = CloneType.TYPE_2;
            } else if (cloneTypeStr.contains("3") || cloneTypeStr.toLowerCase().contains("near")) {
                type = CloneType.TYPE_3;
            } else if (cloneTypeStr.contains("4") || cloneTypeStr.toLowerCase().contains("semantic")) {
                type = CloneType.TYPE_4;
            } else {
                type = CloneType.TYPE_3;
            }

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
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return getHttp().send(req, HttpResponse.BodyHandlers.ofString());
    }
}