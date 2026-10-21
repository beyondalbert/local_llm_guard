package com.vllm.gateway.config;

import com.vllm.gateway.repository.ApiKeyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DataMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    @Autowired
    private ApiKeyMapper apiKeyMapper;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Override
    public void run(String... args) {
        try {
            Long keys = apiKeyMapper.selectCount(null);
            log.info("数据库: {} （API Key 数量: {}）", datasourceUrl, keys);
        } catch (Exception e) {
            log.warn("读取 API Key 数量失败: {}", e.getMessage());
        }
        try {
            List<Map<String, Object>> columns = apiKeyMapper.tableInfoApiKey();
            boolean hasPrefix = columns.stream()
                    .anyMatch(c -> "key_prefix".equalsIgnoreCase(String.valueOf(c.get("name"))));
            if (!hasPrefix) {
                apiKeyMapper.addKeyPrefixColumn();
                log.info("已为 api_key 表补充 key_prefix 列");
            }
        } catch (Exception e) {
            log.warn("api_key 表迁移检查失败（可忽略，若 key_prefix 列已存在）: {}", e.getMessage());
        }
    }
}
