package com.vllm.gateway.controller;

import com.vllm.gateway.entity.ChatMessage;
import com.vllm.gateway.service.ApiKeyService;
import com.vllm.gateway.service.ChatService;
import com.vllm.gateway.util.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private ApiKeyService apiKeyService;

    @GetMapping("/getKeys")
    public R<List<Map<String, Object>>> getKeys() {
        return R.ok(apiKeyService.enabledKeys());
    }

    @GetMapping("/getModels")
    public R<List<String>> getModels() {
        return R.ok(chatService.getModels());
    }

    @GetMapping("/session/list")
    public R<List<Map<String, Object>>> listSessions() {
        return R.ok(chatService.listSessions());
    }

    @PostMapping("/session/create")
    public R<Long> createSession(@RequestBody SessionCreateRequest request) {
        if (request == null) {
            return R.error(400, "请求参数不能为空");
        }
        return R.ok(chatService.createSession(request.title, request.apiKeyId,
                request.modelName, request.temperature, request.maxTokens));
    }

    @PutMapping("/session/update-title")
    public R<Void> updateTitle(@RequestBody UpdateTitleRequest request) {
        if (request == null || request.id == null) {
            return R.error(400, "缺少 id");
        }
        chatService.updateTitle(request.id, request.title);
        return R.ok();
    }

    @DeleteMapping("/session/{sessionId}")
    public R<Void> deleteSession(@PathVariable Long sessionId) {
        chatService.deleteSession(sessionId);
        return R.ok();
    }

    @GetMapping("/session/{sessionId}/messages")
    public R<List<ChatMessage>> messages(@PathVariable Long sessionId) {
        return R.ok(chatService.messages(sessionId));
    }

    @PostMapping("/send")
    public SseEmitter send(@RequestBody SendRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }
        return chatService.send(request.sessionId, request.content,
                request.modelName, request.temperature, request.maxTokens, request.apiKeyId);
    }

    public static class SessionCreateRequest {
        public String title;
        public Long apiKeyId;
        public String modelName;
        public Double temperature;
        public Integer maxTokens;
    }

    public static class UpdateTitleRequest {
        public Long id;
        public String title;
    }

    public static class SendRequest {
        public Long sessionId;
        public String content;
        public String modelName;
        public Double temperature;
        public Integer maxTokens;
        public Long apiKeyId;
    }
}
