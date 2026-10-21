package com.vllm.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vllm.gateway.entity.ChatMessage;
import com.vllm.gateway.entity.ChatSession;
import com.vllm.gateway.entity.RequestLog;
import com.vllm.gateway.repository.ChatMessageMapper;
import com.vllm.gateway.repository.ChatSessionMapper;
import com.vllm.gateway.repository.RequestLogMapper;
import com.vllm.gateway.util.TimeUtil;
import com.vllm.gateway.util.TokenCountUtil;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ChatService {

    @Autowired
    private ConfigService configService;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private RequestLogMapper requestLogMapper;

    @Autowired
    private VllmProxyService vllmProxyService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private TokenCountUtil tokenCountUtil;

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

    public List<Map<String, Object>> listSessions() {
        List<ChatSession> sessions = chatSessionMapper.selectList(
                new QueryWrapper<ChatSession>().orderByDesc("update_time"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatSession session : sessions) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", session.getId());
            map.put("title", session.getTitle());
            map.put("apiKeyId", session.getApiKeyId());
            map.put("modelName", session.getModelName());
            map.put("temperature", session.getTemperature());
            map.put("maxTokens", session.getMaxTokens());
            map.put("createTime", session.getCreateTime());
            map.put("updateTime", session.getUpdateTime());
            result.add(map);
        }
        return result;
    }

    public Long createSession(String title, Long apiKeyId, String modelName,
                              Double temperature, Integer maxTokens) {
        ChatSession session = new ChatSession();
        session.setTitle(title == null || title.isBlank() ? "新对话" : title);
        session.setApiKeyId(apiKeyId);
        session.setModelName(modelName);
        session.setTemperature(temperature);
        session.setMaxTokens(maxTokens);
        session.setCreateTime(TimeUtil.now());
        session.setUpdateTime(TimeUtil.now());
        chatSessionMapper.insert(session);
        return session.getId();
    }

    public void updateTitle(Long id, String title) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setTitle(title);
        session.setUpdateTime(TimeUtil.now());
        chatSessionMapper.updateById(session);
    }

    public void deleteSession(Long id) {
        chatSessionMapper.deleteById(id);
        chatMessageMapper.delete(new QueryWrapper<ChatMessage>().eq("session_id", id));
    }

    public List<ChatMessage> messages(Long sessionId) {
        return chatMessageMapper.selectList(
                new QueryWrapper<ChatMessage>().eq("session_id", sessionId).orderByAsc("id"));
    }

    public List<String> getModels() {
        List<String> models = new ArrayList<>();
        try {
            ResponseEntity<String> response = vllmProxyService.models(null);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode data = root.get("data");
                if (data != null && data.isArray()) {
                    for (JsonNode item : data) {
                        JsonNode id = item.get("id");
                        if (id != null && id.isTextual()) {
                            models.add(id.asText());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // return empty list if upstream is unavailable
        }
        return models;
    }

    public SseEmitter send(Long sessionId, String content, String modelName,
                           Double temperature, Integer maxTokens, Long apiKeyId) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }

        String model = (modelName == null || modelName.isBlank()) ? session.getModelName() : modelName;
        if (model == null || model.isBlank()) {
            model = configService.getDefaultModel();
        }
        Double temp = temperature != null ? temperature
                : (session.getTemperature() != null ? session.getTemperature() : configService.getTemperature());
        Integer maxTokensValue = maxTokens != null ? maxTokens
                : (session.getMaxTokens() != null ? session.getMaxTokens() : configService.getMaxTokens());
        final String finalModel = model;
        final Double finalTemp = temp;
        final Integer finalMaxTokens = maxTokensValue;

        // 对话测试使用所选 API Key：校验可用性与模型白名单
        Long keyId = apiKeyId != null ? apiKeyId : session.getApiKeyId();
        String keyError = apiKeyService.checkUsable(keyId, finalModel);
        if (keyError != null) {
            throw new IllegalArgumentException(keyError);
        }
        if (session.getApiKeyId() == null) {
            ChatSession keyTouch = new ChatSession();
            keyTouch.setId(sessionId);
            keyTouch.setApiKeyId(keyId);
            keyTouch.setUpdateTime(TimeUtil.now());
            chatSessionMapper.updateById(keyTouch);
            session.setApiKeyId(keyId);
        }
        final Long finalApiKeyId = keyId;

        // persist user message immediately
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("user");
        userMessage.setContent(content);
        userMessage.setPromptTokens(tokenCountUtil.count(content));
        userMessage.setCompletionTokens(0);
        userMessage.setCreateTime(TimeUtil.now());
        chatMessageMapper.insert(userMessage);

        // auto title from first user message
        if (session.getTitle() == null || session.getTitle().isBlank() || "新对话".equals(session.getTitle())) {
            String title = content.length() > 30 ? content.substring(0, 30) : content;
            ChatSession titleUpdate = new ChatSession();
            titleUpdate.setId(sessionId);
            titleUpdate.setTitle(title);
            titleUpdate.setUpdateTime(TimeUtil.now());
            chatSessionMapper.updateById(titleUpdate);
        } else {
            ChatSession touch = new ChatSession();
            touch.setId(sessionId);
            touch.setUpdateTime(TimeUtil.now());
            chatSessionMapper.updateById(touch);
        }

        List<ChatMessage> history = messages(sessionId);
        SseEmitter emitter = new SseEmitter(600000L);
        // 提前登记超时/出错/完成回调，确保对已终结的 emitter 不再 send/complete
        emitter.onTimeout(() -> { });
        emitter.onError(e -> { });
        emitter.onCompletion(() -> { });
        executor.execute(() -> doSend(emitter, session, history, finalModel, finalTemp, finalMaxTokens, finalApiKeyId));
        return emitter;
    }

    private void doSend(SseEmitter emitter, ChatSession session, List<ChatMessage> history,
                        String model, Double temperature, Integer maxTokens, Long apiKeyId) {
        long start = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("stream", true);
            var messages = body.putArray("messages");
            for (ChatMessage message : history) {
                var item = messages.addObject();
                item.put("role", message.getRole());
                item.put("content", message.getContent() == null ? "" : message.getContent());
            }
            if (temperature != null) {
                body.put("temperature", temperature);
            }
            if (maxTokens != null) {
                body.put("max_tokens", maxTokens);
            }

            HttpRequest.Builder chatRequest = HttpRequest.newBuilder()
                    .uri(URI.create(configService.getUpstreamUrl() + "/chat/completions"))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            String upstreamKey = configService.getUpstreamApiKey();
            if (upstreamKey != null && !upstreamKey.isBlank()) {
                chatRequest.header("Authorization", "Bearer " + upstreamKey.trim());
            }
            HttpRequest request = chatRequest.build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                saveLog(session, apiKeyId, model, history, 0, 0, start, 0, errorBody);
                safeSend(emitter, SseEmitter.event().data(sseError(errorBody)), done);
                safeComplete(emitter, done);
                return;
            }

            StringBuilder assistant = new StringBuilder();
            int completionTokens = 0;
            long lastMetaTime = 0L;
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
                        // 客户端已断开或超时，停止继续读取上游流
                        break;
                    }
                    String delta = appendDelta(data, assistant);
                    if (delta != null && !delta.isEmpty()) {
                        long now = System.currentTimeMillis();
                        if (lastMetaTime == 0L) {
                            lastMetaTime = now;
                        }
                        completionTokens += tokenCountUtil.count(delta);
                        if (now - lastMetaTime >= 500) {
                            safeSend(emitter, SseEmitter.event().data(metaJson(completionTokens)), done);
                            lastMetaTime = now;
                        }
                    }
                }
            }
            // 发送最终 token 统计，便于前端显示生成速度
            safeSend(emitter, SseEmitter.event().data(metaJson(completionTokens)), done);

            // 仅在客户端仍在线时才持久化本轮助手消息（避免对已断开的连接做无谓写库）
            if (!done.get()) {
                ChatMessage assistantMessage = new ChatMessage();
                assistantMessage.setSessionId(session.getId());
                assistantMessage.setRole("assistant");
                assistantMessage.setContent(assistant.toString());
                assistantMessage.setPromptTokens(0);
                assistantMessage.setCompletionTokens(tokenCountUtil.count(assistant.toString()));
                assistantMessage.setCreateTime(TimeUtil.now());
                chatMessageMapper.insert(assistantMessage);

                ChatSession touch = new ChatSession();
                touch.setId(session.getId());
                touch.setUpdateTime(TimeUtil.now());
                chatSessionMapper.updateById(touch);

                saveLog(session, apiKeyId, model, history, assistantMessage.getCompletionTokens(), 0, start, 1, null);
            }
            safeSend(emitter, SseEmitter.event().data("[DONE]"), done);
            safeComplete(emitter, done);
        } catch (Exception e) {
            saveLog(session, apiKeyId, model, history, 0, 0, start, 0, e.getMessage());
            // 只在连接仍然有效时才推送错误并结束，否则直接忽略
            boolean sent = safeSend(emitter, SseEmitter.event().data(sseError("vLLM 上游请求失败: " + e.getMessage())), done);
            if (sent) {
                safeComplete(emitter, done);
            }
        }
    }

    /**
     * 安全发送：emitter 已终结（客户端断开/超时/已 completed）时静默忽略并标记 done，返回 false。
     */
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


    private void saveLog(ChatSession session, Long apiKeyId, String model, List<ChatMessage> history,
                         int completionTokens, int unused, long start, int status, String errorMsg) {
        RequestLog log = new RequestLog();
        log.setApiKeyId(apiKeyId);
        log.setModel(model);
        int promptTokens = history == null ? 0
                : history.stream().mapToInt(m -> m.getPromptTokens() == null ? 0 : m.getPromptTokens()).sum();
        log.setPromptTokens(promptTokens);
        log.setCompletionTokens(completionTokens);
        log.setRequestIp("127.0.0.1");
        log.setCostMs(System.currentTimeMillis() - start);
        log.setStatus(status);
        log.setErrorMsg(errorMsg);
        log.setRequestTime(TimeUtil.now());
        requestLogMapper.insert(log);
    }

    private String appendDelta(String data, StringBuilder assistant) {
        try {
            JsonNode chunk = objectMapper.readTree(data);
            JsonNode choices = chunk.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    JsonNode content = delta.get("content");
                    if (content != null && content.isTextual()) {
                        String text = content.asText();
                        assistant.append(text);
                        return text;
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore malformed chunks
        }
        return null;
    }

    private String metaJson(int completionTokens) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "meta",
                    "completionTokens", completionTokens
            ));
        } catch (Exception e) {
            return "{\"type\":\"meta\",\"completionTokens\":" + completionTokens + "}";
        }
    }

    private String sseError(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "error", Map.of(
                            "message", message == null ? "error" : message,
                            "type", "upstream_error"
                    )
            ));
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"upstream_error\"}}";
        }
    }
}
