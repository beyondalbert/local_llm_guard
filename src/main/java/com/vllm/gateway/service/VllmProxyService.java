package com.vllm.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vllm.gateway.entity.ApiKey;
import com.vllm.gateway.entity.RequestLog;
import com.vllm.gateway.repository.RequestLogMapper;
import com.vllm.gateway.util.TimeUtil;
import com.vllm.gateway.util.TokenCountUtil;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class VllmProxyService {

    @Autowired
    private RequestLogMapper requestLogMapper;

    @Autowired
    private TokenCountUtil tokenCountUtil;

    @Autowired
    private ConfigService configService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public ResponseEntity<String> chatCompletions(JsonNode body, HttpServletRequest servletRequest) throws Exception {
        long start = System.currentTimeMillis();
        ApiKey apiKey = (ApiKey) servletRequest.getAttribute("apiKey");
        String ip = clientIp(servletRequest);
        String model = body.path("model").asText(null);
        int promptTokens = tokenCountUtil.countMessages(body.get("messages"));

        try {
            validateModel(apiKey, model);
            HttpResponse<String> response = post(configService.getUpstreamUrl() + "/chat/completions", body.toString(),
                    upstreamAuth(servletRequest));
            long cost = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                saveLog(apiKey, model, promptTokens, 0, ip, cost, 0, response.body());
                return ResponseEntity.status(response.statusCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(response.body());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            int completionTokens = countCompletion(responseJson);
            saveLog(apiKey, model, promptTokens, completionTokens, ip, cost, 1, null);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (GatewayException e) {
            long cost = System.currentTimeMillis() - start;
            saveLog(apiKey, model, promptTokens, 0, ip, cost, 0, e.getMessage());
            return ResponseEntity.status(e.status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(openAiError(e.code, e.getMessage()));
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            saveLog(apiKey, model, promptTokens, 0, ip, cost, 0, e.getMessage());
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(openAiError("upstream_error", "Failed to reach vLLM upstream: " + e.getMessage()));
        }
    }

    public SseEmitter streamChat(JsonNode body, HttpServletRequest servletRequest) {
        SseEmitter emitter = new SseEmitter(600000L);
        executor.execute(() -> doStream(emitter, body, servletRequest));
        return emitter;
    }

    private void doStream(SseEmitter emitter, JsonNode body, HttpServletRequest servletRequest) {
        long start = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        ApiKey apiKey = (ApiKey) servletRequest.getAttribute("apiKey");
        String ip = clientIp(servletRequest);
        String model = body.path("model").asText(null);
        int promptTokens = tokenCountUtil.countMessages(body.get("messages"));

        try {
            validateModel(apiKey, model);
            HttpRequest request = buildPost(configService.getUpstreamUrl() + "/chat/completions", body.toString(),
                    upstreamAuth(servletRequest));
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = readAll(response.body());
                long cost = System.currentTimeMillis() - start;
                saveLog(apiKey, model, promptTokens, 0, ip, cost, 0, errorBody);
                safeSend(emitter, SseEmitter.event().data(errorBody), done);
                safeComplete(emitter, done);
                return;
            }

            StringBuilder assistant = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    if (!safeSend(emitter, SseEmitter.event().data(data), done)) {
                        break;
                    }
                    appendDelta(data, assistant);
                }
            }

            long cost = System.currentTimeMillis() - start;
            saveLog(apiKey, model, promptTokens, tokenCountUtil.count(assistant.toString()), ip, cost, 1, null);
            safeSend(emitter, SseEmitter.event().data("[DONE]"), done);
            safeComplete(emitter, done);
        } catch (GatewayException e) {
            long cost = System.currentTimeMillis() - start;
            saveLog(apiKey, model, promptTokens, 0, ip, cost, 0, e.getMessage());
            sendErrorAndComplete(emitter, openAiError(e.code, e.getMessage()), done);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            saveLog(apiKey, model, promptTokens, 0, ip, cost, 0, e.getMessage());
            sendErrorAndComplete(emitter, openAiError("upstream_error", "Failed to reach vLLM upstream: " + e.getMessage()), done);
        }
    }

    public ResponseEntity<String> models(HttpServletRequest servletRequest) {
        try {
            HttpResponse<String> response = get(configService.getUpstreamUrl() + "/models",
                    upstreamAuth(servletRequest));
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(openAiError("upstream_error", "Failed to reach vLLM upstream: " + e.getMessage()));
        }
    }

    private void validateModel(ApiKey apiKey, String model) {
        if (model == null || model.isBlank()) {
            throw new GatewayException(400, "invalid_request", "Field 'model' is required.");
        }
        if (apiKey.getAllowModels() != null && !apiKey.getAllowModels().isBlank()) {
            boolean allowed = Arrays.stream(apiKey.getAllowModels().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .anyMatch(s -> s.equals(model));
            if (!allowed) {
                throw new GatewayException(403, "model_not_allowed", "Model '" + model + "' is not allowed for this API key.");
            }
        }
    }

    private void appendDelta(String data, StringBuilder assistant) {
        try {
            JsonNode chunk = objectMapper.readTree(data);
            JsonNode choices = chunk.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    JsonNode content = delta.get("content");
                    if (content != null && content.isTextual()) {
                        assistant.append(content.asText());
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore malformed stream chunks
        }
    }

    private int countCompletion(JsonNode responseJson) {
        JsonNode usage = responseJson.get("usage");
        if (usage != null && usage.has("completion_tokens") && usage.get("completion_tokens").isNumber()) {
            return usage.get("completion_tokens").asInt();
        }
        JsonNode choices = responseJson.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).get("message");
            if (message != null) {
                JsonNode content = message.get("content");
                if (content != null && content.isTextual()) {
                    return tokenCountUtil.count(content.asText());
                }
            }
        }
        return 0;
    }

    private void saveLog(ApiKey apiKey, String model, int promptTokens, int completionTokens,
                         String ip, long costMs, int status, String errorMsg) {
        RequestLog log = new RequestLog();
        log.setApiKeyId(apiKey == null ? null : apiKey.getId());
        log.setModel(model);
        log.setPromptTokens(promptTokens);
        log.setCompletionTokens(completionTokens);
        log.setRequestIp(ip);
        log.setCostMs(costMs);
        log.setStatus(status);
        log.setErrorMsg(errorMsg);
        log.setRequestTime(TimeUtil.now());
        requestLogMapper.insert(log);
    }

    private HttpRequest buildPost(String url, String json, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }
        return builder.build();
    }

    private HttpResponse<String> post(String url, String json, String authorization) throws Exception {
        return httpClient.send(buildPost(url, json, authorization),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String url, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 上游鉴权头：优先用配置里的上游 API Key，未配置时透传客户端的 Key。
     */
    private String upstreamAuth(HttpServletRequest request) {
        if (request == null) {
            String configured = configService.getUpstreamApiKey();
            return configured == null || configured.isBlank() ? null : "Bearer " + configured.trim();
        }
        String configured = configService.getUpstreamApiKey();
        if (configured != null && !configured.isBlank()) {
            return "Bearer " + configured.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && !authorization.isBlank()) {
            return authorization.trim();
        }
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "Bearer " + apiKey.trim();
        }
        return null;
    }

    private String readAll(InputStream inputStream) throws Exception {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String openAiError(String code, String message) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "error", java.util.Map.of(
                            "message", message == null ? "error" : message,
                            "type", "invalid_request_error",
                            "code", code == null ? "error" : code
                    )
            ));
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"invalid_request_error\",\"code\":\"" + code + "\"}}";
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String data,
                                      java.util.concurrent.atomic.AtomicBoolean done) {
        if (done.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(data));
            emitter.complete();
            done.set(true);
        } catch (Exception e) {
            done.set(true);
            // 已终结，不再做任何操作
        }
    }

    private boolean safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder builder,
                             java.util.concurrent.atomic.AtomicBoolean done) {
        if (done.get()) {
            return false;
        }
        try {
            emitter.send(builder);
            return true;
        } catch (Exception ignored) {
            done.set(true);
            return false;
        }
    }

    private void safeComplete(SseEmitter emitter, java.util.concurrent.atomic.AtomicBoolean done) {
        if (done.get()) {
            return;
        }
        try {
            emitter.complete();
            done.set(true);
        } catch (Exception ignored) {
            done.set(true);
        }
    }

    public static class GatewayException extends RuntimeException {
        private final int status;
        private final String code;

        public GatewayException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
