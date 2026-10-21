package com.vllm.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.vllm.gateway.entity.ApiKey;
import com.vllm.gateway.repository.ApiKeyMapper;
import com.vllm.gateway.util.KeyGenUtil;
import com.vllm.gateway.util.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApiKeyService {

    @Autowired
    private ApiKeyMapper apiKeyMapper;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    public String now() {
        return TimeUtil.now();
    }

    public ApiKey findByRawKey(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return null;
        }
        String hash = KeyGenUtil.sha256(normalizeKey(rawKey));
        return apiKeyMapper.selectOne(new QueryWrapper<ApiKey>().eq("key_hash", hash));
    }

    /**
     * 容忍多余空格、包裹的引号与 "Bearer" 前缀。
     */
    public String normalizeKey(String raw) {
        String key = raw == null ? "" : raw.trim();
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

    /**
     * 当前实例使用的数据库信息，便于排查「配置了 Key 却提示 invalid_api_key」。
     */
    public String databaseInfo() {
        return datasourceUrl + " (api_key 共 " + countAll() + " 条)";
    }

    /**
     * 校验一个原始 Key，返回诊断结果（用于管理台自助排查）。
     */
    public Map<String, Object> verify(String rawKey) {
        Map<String, Object> result = new HashMap<>();
        String key = normalizeKey(rawKey);
        result.put("database", datasourceUrl);
        result.put("totalKeys", countAll());
        if (key.isEmpty()) {
            result.put("valid", false);
            result.put("reason", "Key 为空");
            return result;
        }
        result.put("keyLength", key.length());
        result.put("keyPrefix", key.substring(0, Math.min(12, key.length())));
        ApiKey apiKey = findByRawKey(key);
        if (apiKey == null) {
            result.put("valid", false);
            result.put("reason", "数据库中不存在该 Key（当前库共 " + countAll() + " 条记录）");
            return result;
        }
        result.put("id", apiKey.getId());
        result.put("remark", apiKey.getRemark());
        result.put("enabled", apiKey.getEnabled());
        result.put("expireTime", apiKey.getExpireTime());
        if (apiKey.getEnabled() == null || apiKey.getEnabled() != 1) {
            result.put("valid", false);
            result.put("reason", "该 Key 已被禁用");
            return result;
        }
        if (apiKey.getExpireTime() != null && !apiKey.getExpireTime().isEmpty()
                && apiKey.getExpireTime().compareTo(now()) < 0) {
            result.put("valid", false);
            result.put("reason", "该 Key 已于 " + apiKey.getExpireTime() + " 过期");
            return result;
        }
        result.put("valid", true);
        result.put("reason", "校验通过");
        return result;
    }

    public ApiKey findById(Long id) {
        return id == null ? null : apiKeyMapper.selectById(id);
    }

    public long countAll() {
        Long count = apiKeyMapper.selectCount(null);
        return count == null ? 0L : count;
    }

    /**
     * 校验某个 Key 是否可用于对话测试，返回错误信息；可用则返回 null。
     */
    public String checkUsable(Long apiKeyId, String model) {
        if (apiKeyId == null) {
            return "请先选择 API Key";
        }
        ApiKey key = apiKeyMapper.selectById(apiKeyId);
        if (key == null) {
            return "API Key 不存在";
        }
        if (key.getEnabled() == null || key.getEnabled() != 1) {
            return "API Key 已禁用";
        }
        if (key.getExpireTime() != null && !key.getExpireTime().isEmpty()
                && key.getExpireTime().compareTo(now()) < 0) {
            return "API Key 已过期";
        }
        if (model != null && !model.isBlank()
                && key.getAllowModels() != null && !key.getAllowModels().isBlank()) {
            boolean allowed = Arrays.stream(key.getAllowModels().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .anyMatch(s -> s.equals(model));
            if (!allowed) {
                return "模型 '" + model + "' 不在该 API Key 的允许列表中";
            }
        }
        return null;
    }

    public List<Map<String, Object>> list() {
        List<ApiKey> keys = apiKeyMapper.selectList(
                new QueryWrapper<ApiKey>().orderByDesc("id"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApiKey key : keys) {
            result.add(toMap(key));
        }
        return result;
    }

    public Map<String, Object> create(String remark, Integer enabled, Integer expireDays, String allowModels) {
        String rawKey = KeyGenUtil.generateKey();
        ApiKey apiKey = new ApiKey();
        apiKey.setKeyHash(KeyGenUtil.sha256(rawKey));
        apiKey.setKeyPrefix(rawKey.substring(0, Math.min(12, rawKey.length())));
        apiKey.setRemark(remark);
        apiKey.setEnabled(enabled == null ? 1 : enabled);
        if (expireDays != null && expireDays > 0) {
            apiKey.setExpireTime(TimeUtil.format(LocalDateTime.now().plusDays(expireDays)));
        }
        apiKey.setAllowModels(allowModels);
        apiKey.setCreateTime(TimeUtil.now());
        apiKeyMapper.insert(apiKey);

        Map<String, Object> result = toMap(apiKey);
        result.put("key", rawKey);
        return result;
    }

    public void update(Long id, String remark, Integer enabled, String expireTime, String allowModels) {
        UpdateWrapper<ApiKey> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id);
        if (remark != null) {
            wrapper.set("remark", remark);
        }
        if (enabled != null) {
            wrapper.set("enabled", enabled);
        }
        if (expireTime != null) {
            wrapper.set("expire_time", expireTime);
        }
        if (allowModels != null) {
            wrapper.set("allow_models", allowModels);
        }
        apiKeyMapper.update(null, wrapper);
    }

    public void delete(Long id) {
        apiKeyMapper.deleteById(id);
    }

    public List<Map<String, Object>> enabledKeys() {
        List<ApiKey> keys = apiKeyMapper.selectList(
                new QueryWrapper<ApiKey>().eq("enabled", 1).orderByDesc("id"));
        List<Map<String, Object>> result = new ArrayList<>();
        String now = now();
        for (ApiKey key : keys) {
            if (key.getExpireTime() != null && !key.getExpireTime().isEmpty()
                    && key.getExpireTime().compareTo(now) < 0) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("id", key.getId());
            item.put("remark", key.getRemark());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> toMap(ApiKey key) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", key.getId());
        map.put("key", maskKey(key.getKeyPrefix()));
        map.put("keyPrefix", key.getKeyPrefix());
        map.put("remark", key.getRemark());
        map.put("enabled", key.getEnabled());
        map.put("expireTime", key.getExpireTime());
        map.put("allowModels", key.getAllowModels());
        map.put("createTime", key.getCreateTime());
        return map;
    }

    private String maskKey(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "********************";
        }
        return prefix + "****";
    }
}
