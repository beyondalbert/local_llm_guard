package com.vllm.gateway.service;

import com.vllm.gateway.entity.SystemConfig;
import com.vllm.gateway.repository.SystemConfigMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ConfigService {

    public static final String K_UPSTREAM_URL = "upstream.url";
    public static final String K_UPSTREAM_API_KEY = "upstream.api-key";
    public static final String K_DEFAULT_MODEL = "model.default";
    public static final String K_TEMPERATURE = "model.temperature";
    public static final String K_MAX_TOKENS = "model.max_tokens";
    public static final String K_TOP_P = "model.top_p";
    public static final String K_PRESENCE_PENALTY = "model.presence_penalty";
    public static final String K_FREQUENCY_PENALTY = "model.frequency_penalty";

    @Value("${vllm.upstream-url}")
    private String defaultUpstreamUrl;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @PostConstruct
    public void ensureDefaults() {
        putIfAbsent(K_UPSTREAM_URL, defaultUpstreamUrl);
        putIfAbsent(K_UPSTREAM_API_KEY, "");
        putIfAbsent(K_DEFAULT_MODEL, "");
        putIfAbsent(K_TEMPERATURE, "0.7");
        putIfAbsent(K_MAX_TOKENS, "2048");
        putIfAbsent(K_TOP_P, "1.0");
        putIfAbsent(K_PRESENCE_PENALTY, "0.0");
        putIfAbsent(K_FREQUENCY_PENALTY, "0.0");
    }

    public String getUpstreamUrl() {
        return get(K_UPSTREAM_URL, defaultUpstreamUrl);
    }

    /**
     * 上游 vLLM 自己的 API Key（可空）。
     * 留空时网关会把客户端传来的 Key 透传给上游。
     */
    public String getUpstreamApiKey() {
        return get(K_UPSTREAM_API_KEY, "");
    }

    public String getDefaultModel() {
        return get(K_DEFAULT_MODEL, "");
    }

    public double getTemperature() {
        return parseDouble(get(K_TEMPERATURE, "0.7"), 0.7);
    }

    public int getMaxTokens() {
        return parseInt(get(K_MAX_TOKENS, "2048"), 2048);
    }

    public double getTopP() {
        return parseDouble(get(K_TOP_P, "1.0"), 1.0);
    }

    public double getPresencePenalty() {
        return parseDouble(get(K_PRESENCE_PENALTY, "0.0"), 0.0);
    }

    public double getFrequencyPenalty() {
        return parseDouble(get(K_FREQUENCY_PENALTY, "0.0"), 0.0);
    }

    public Map<String, Object> getAll() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("upstreamUrl", getUpstreamUrl());
        map.put("upstreamApiKey", getUpstreamApiKey());
        map.put("defaultModel", getDefaultModel());
        map.put("temperature", getTemperature());
        map.put("maxTokens", getMaxTokens());
        map.put("topP", getTopP());
        map.put("presencePenalty", getPresencePenalty());
        map.put("frequencyPenalty", getFrequencyPenalty());
        return map;
    }

    public void save(Map<String, Object> values) {
        if (values == null) {
            return;
        }
        saveValue(values, "upstreamUrl", K_UPSTREAM_URL);
        saveValue(values, "upstreamApiKey", K_UPSTREAM_API_KEY);
        saveValue(values, "defaultModel", K_DEFAULT_MODEL);
        saveValue(values, "temperature", K_TEMPERATURE);
        saveValue(values, "maxTokens", K_MAX_TOKENS);
        saveValue(values, "topP", K_TOP_P);
        saveValue(values, "presencePenalty", K_PRESENCE_PENALTY);
        saveValue(values, "frequencyPenalty", K_FREQUENCY_PENALTY);
    }

    private void saveValue(Map<String, Object> values, String key, String dbKey) {
        if (values.containsKey(key)) {
            Object value = values.get(key);
            set(dbKey, value == null ? "" : String.valueOf(value));
        }
    }

    private void putIfAbsent(String key, String value) {
        if (systemConfigMapper.selectById(key) == null) {
            SystemConfig config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            systemConfigMapper.insert(config);
        }
    }

    private void set(String key, String value) {
        SystemConfig config = systemConfigMapper.selectById(key);
        if (config == null) {
            config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            systemConfigMapper.insert(config);
        } else {
            config.setConfigValue(value);
            systemConfigMapper.updateById(config);
        }
    }

    private String get(String key, String defaultValue) {
        SystemConfig config = systemConfigMapper.selectById(key);
        if (config == null || config.getConfigValue() == null || config.getConfigValue().isBlank()) {
            return defaultValue;
        }
        return config.getConfigValue();
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double parseDouble(String value, double defaultValue) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
