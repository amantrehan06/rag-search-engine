package com.productsearch.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LangSmithFeedbackService {

    private static final String FEEDBACK_PATH   = "/feedback";
    private static final String RUNS_QUERY_PATH = "/runs/query";

    @Value("${LANGSMITH_API_KEY:}")
    private String langsmithApiKey;

    @Value("${LANGSMITH_ENDPOINT:https://api.smith.langchain.com}")
    private String langsmithEndpoint;

    @Value("${LANGSMITH_SESSION_ID:}")
    private String langsmithSessionId;

    private final OkHttpClient httpClient   = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Public API ────────────────────────────────────────────────────────────

    public void submit(String traceId, String key, double score, String comment) {
        if (StringUtils.isBlank(langsmithApiKey)) {
            log.warn("LANGSMITH_API_KEY not set — skipping feedback for key='{}'", key);
            return;
        }

        String runId = resolveRunId(traceId);
        log.info("Submitting feedback — runId={} key='{}' score={}", runId, key, score);

        try {
            Map<String, Object> body = Map.of(
                    "run_id",  runId,
                    "key",     key,
                    "score",   score,
                    "comment", comment != null ? comment : ""
            );

            String json = objectMapper.writeValueAsString(body);
            RequestBody requestBody = RequestBody.create(json, MediaType.get("application/json"));
            Request request = new Request.Builder()
                    .url(langsmithEndpoint + FEEDBACK_PATH)
                    .addHeader("x-api-key", langsmithApiKey)
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("Feedback submitted — runId={} key='{}' score={}", runId, key, score);
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.warn("Feedback submission failed — key='{}' status={} body='{}'",
                            key, response.code(), errorBody);
                }
            }
        } catch (Exception e) {
            log.warn("Could not submit LangSmith feedback for key='{}': {}", key, e.getMessage());
        }
    }

    // ── Run ID resolution ─────────────────────────────────────────────────────

    private String resolveRunId(String hexTraceId) {
        if (StringUtils.isBlank(langsmithSessionId)) {
            log.warn("LANGSMITH_SESSION_ID not set — cannot resolve run ID for feedback");
            return hexToUuid(hexTraceId);
        }

        try {
            Map<String, Object> body = Map.of(
                    "session", List.of(langsmithSessionId),
                    "filter",  String.format(
                            "and(eq(metadata_key, \"OTEL_TRACE_ID\"), eq(metadata_value, \"%s\"))",
                            hexTraceId),
                    "limit",   1
            );

            String json = objectMapper.writeValueAsString(body);
            RequestBody requestBody = RequestBody.create(json, MediaType.get("application/json"));
            Request req = new Request.Builder()
                    .url(langsmithEndpoint + RUNS_QUERY_PATH)
                    .addHeader("x-api-key", langsmithApiKey)
                    .post(requestBody)
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("runs/query failed: HTTP {}", resp.code());
                    return hexToUuid(hexTraceId);
                }

                JsonNode result = objectMapper.readTree(resp.body().string());
                JsonNode runs   = result.path("runs");

                if (runs.isArray() && !runs.isEmpty()) {
                    String rootRunId = runs.get(0).path("trace_id").asText(null);
                    if (rootRunId != null) {
                        log.info("Resolved root runId={} for traceId={}", rootRunId, hexTraceId);
                        return rootRunId;
                    }
                }

                log.warn("No runs found for OTEL_TRACE_ID={}", hexTraceId);
            }
        } catch (Exception e) {
            log.warn("resolveRunId error: {}", e.getMessage());
        }

        return hexToUuid(hexTraceId);
    }

    private String hexToUuid(String hex) {
        if (hex == null || hex.length() != 32) return hex;
        return hex.substring(0, 8)  + "-" +
               hex.substring(8,  12) + "-" +
               hex.substring(12, 16) + "-" +
               hex.substring(16, 20) + "-" +
               hex.substring(20);
    }
}
