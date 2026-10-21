package com.vllm.gateway.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vllm.gateway.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {

    @Select("PRAGMA table_info(api_key)")
    List<Map<String, Object>> tableInfoApiKey();

    @Update("ALTER TABLE api_key ADD COLUMN key_prefix TEXT")
    void addKeyPrefixColumn();
}
