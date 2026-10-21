package com.vllm.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vllm.gateway.service.ApiKeyService;
import com.vllm.gateway.entity.ApiKey;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

@Component
public class ApiKeyAuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** 兼容多种客户端：Authorization: Bearer xxx / Authorization: xxx / X-API-Key / api-key */
    private static final String[] KEY_HEADERS = {"Authorization", "X-API-Key", "api-key", "x-api-key"};

    @Autowired
    private ApiKeyService apiKeyService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String key = extractKey(httpRequest);

        if (key == null || key.isEmpty()) {
            log.warn("API Key 缺失: {} {} headers={}", httpRequest.getMethod(),
                    httpRequest.getRequestURI(), headerNames(httpRequest));
            writeError(httpResponse, "missing_api_key",
                    "Missing API key. Use Authorization: Bearer <key> or X-API-Key: <key>.");
            return;
        }

        ApiKey apiKey = apiKeyService.findByRawKey(key);
        if (apiKey == null) {
            long total = apiKeyService.countAll();
            log.warn("API Key 校验失败（未找到）: prefix={} prefixLen={} keyLen={} uri={} dbKeys={}",
                    keyPrefix(key), keyPrefix(key).length(), key.length(),
                    httpRequest.getRequestURI(), total);
            writeError(httpResponse, "invalid_api_key",
                    "Invalid API key. No matching key in database (prefix " + keyPrefix(key)
                            + ", total keys: " + total + "). Check that the gateway uses the same database: "
                            + apiKeyService.databaseInfo() + ".");
            return;
        }
        if (apiKey.getEnabled() == null || apiKey.getEnabled() != 1) {
            log.warn("API Key 校验失败（已禁用）: id={} prefix={}", apiKey.getId(), keyPrefix(key));
            writeError(httpResponse, "invalid_api_key", "API key is disabled.");
            return;
        }
        if (apiKey.getExpireTime() != null && !apiKey.getExpireTime().isEmpty()
                && apiKey.getExpireTime().compareTo(apiKeyService.now()) < 0) {
            log.warn("API Key 校验失败（已过期）: id={} expireTime={} now={}",
                    apiKey.getId(), apiKey.getExpireTime(), apiKeyService.now());
            writeError(httpResponse, "invalid_api_key", "API key has expired.");
            return;
        }

        httpRequest.setAttribute("apiKey", apiKey);
        httpRequest.setAttribute("apiKeyId", apiKey.getId());
        chain.doFilter(request, response);
    }

    /**
     * 从请求头中提取 Key，容忍大小写、多余空格、包裹的引号与 "Bearer" 前缀。
     */
    private String extractKey(HttpServletRequest request) {
        for (String header : KEY_HEADERS) {
            String value = request.getHeader(header);
            if (value == null || value.isBlank()) {
                continue;
            }
            String key = normalize(value);
            if (!key.isEmpty()) {
                return key;
            }
        }
        return null;
    }

    private String normalize(String raw) {
        String key = raw.trim();
        if (key.regionMatches(true, 0, "Bearer", 0, 6)) {
            key = key.substring(6).trim();
        }
        while (key.length() >= 2
                && ((key.startsWith("\"") && key.endsWith("\""))
                || (key.startsWith("'") && key.endsWith("'")))) {
            key = key.substring(1, key.length() - 1).trim();
        }
        return key;
    }

    private String headerNames(HttpServletRequest request) {
        List<String> names = new ArrayList<>();
        Enumeration<String> it = request.getHeaderNames();
        while (it != null && it.hasMoreElements()) {
            names.add(it.nextElement());
        }
        return names.toString();
    }

    private String keyPrefix(String key) {
        if (key == null) {
            return "";
        }
        return key.substring(0, Math.min(12, key.length()));
    }

    private void writeError(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> error = Map.of(
                "error", Map.of(
                        "message", message,
                        "type", "invalid_request_error",
                        "code", code
                )
        );
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
