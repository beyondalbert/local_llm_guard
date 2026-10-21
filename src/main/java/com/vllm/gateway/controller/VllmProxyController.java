package com.vllm.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.vllm.gateway.service.VllmProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class VllmProxyController {

    @Autowired
    private VllmProxyService vllmProxyService;

    @PostMapping("/chat/completions")
    public Object chatCompletions(@RequestBody(required = false) JsonNode body,
                                  HttpServletRequest request) throws Exception {
        if (body == null) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"message\":\"Request body is required\",\"type\":\"invalid_request_error\",\"code\":\"invalid_request\"}}");
        }
        boolean stream = body.path("stream").asBoolean(false);
        if (stream) {
            return vllmProxyService.streamChat(body, request);
        }
        return vllmProxyService.chatCompletions(body, request);
    }

    @GetMapping("/models")
    public ResponseEntity<String> models(HttpServletRequest request) {
        return vllmProxyService.models(request);
    }
}
