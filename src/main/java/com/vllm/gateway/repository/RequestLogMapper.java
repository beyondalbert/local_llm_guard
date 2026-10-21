package com.vllm.gateway.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vllm.gateway.entity.RequestLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface RequestLogMapper extends BaseMapper<RequestLog> {

    @Select("SELECT COUNT(*) FROM request_log")
    Long countAll();

    @Select("SELECT IFNULL(SUM(prompt_tokens),0) FROM request_log")
    Long sumPromptTokens();

    @Select("SELECT IFNULL(SUM(completion_tokens),0) FROM request_log")
    Long sumCompletionTokens();

    @Select("SELECT IFNULL(SUM(prompt_tokens),0) FROM request_log " +
            "WHERE request_time >= #{start} AND request_time < #{end}")
    Long sumPromptTokensBetween(@Param("start") String start, @Param("end") String end);

    @Select("SELECT IFNULL(SUM(completion_tokens),0) FROM request_log " +
            "WHERE request_time >= #{start} AND request_time < #{end}")
    Long sumCompletionTokensBetween(@Param("start") String start, @Param("end") String end);

    @Select("SELECT COUNT(*) FROM request_log WHERE request_time >= #{start} AND request_time < #{end}")
    Long countBetween(@Param("start") String start, @Param("end") String end);

    @Select("SELECT IFNULL(SUM(prompt_tokens + completion_tokens),0) FROM request_log " +
            "WHERE request_time >= #{start} AND request_time < #{end}")
    Long sumTokensBetween(@Param("start") String start, @Param("end") String end);

    @Select("SELECT date(request_time) AS day, COUNT(*) AS cnt FROM request_log " +
            "WHERE request_time >= #{start} GROUP BY date(request_time) ORDER BY day")
    List<Map<String, Object>> countByDay(@Param("start") String start);

    @Select("SELECT model AS name, COUNT(*) AS value FROM request_log GROUP BY model ORDER BY value DESC")
    List<Map<String, Object>> countByModel();

    @Select("SELECT status AS name, COUNT(*) AS value FROM request_log GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Select("<script>" +
            "SELECT date(request_time) AS day, COUNT(*) AS requests, " +
            "IFNULL(SUM(prompt_tokens),0) AS promptTokens, " +
            "IFNULL(SUM(completion_tokens),0) AS completionTokens, " +
            "IFNULL(SUM(prompt_tokens + completion_tokens),0) AS totalTokens, " +
            "IFNULL(SUM(prompt_tokens * #{inputPrice} + completion_tokens * #{outputPrice}),0) AS totalCost " +
            "FROM request_log WHERE request_time &gt;= #{start} " +
            "<if test='keys != null and keys.size() > 0'>" +
            " AND api_key_id IN " +
            "<foreach collection='keys' item='k' open='(' separator=',' close=')'>#{k}</foreach>" +
            "</if>" +
            " GROUP BY date(request_time) ORDER BY day" +
            "</script>")
    List<Map<String, Object>> dailyStats(@Param("start") String start, @Param("keys") List<Long> keys,
                                         @Param("inputPrice") double inputPrice, @Param("outputPrice") double outputPrice);

    @Select("SELECT r.api_key_id AS apiKeyId, a.remark AS remark, a.key_prefix AS keyPrefix, " +
            "COUNT(*) AS totalRequests, " +
            "SUM(CASE WHEN r.status = 1 THEN 1 ELSE 0 END) AS successRequests, " +
            "SUM(CASE WHEN r.status != 1 THEN 1 ELSE 0 END) AS failedRequests, " +
            "IFNULL(SUM(r.prompt_tokens),0) AS promptTokens, " +
            "IFNULL(SUM(r.completion_tokens),0) AS completionTokens, " +
            "IFNULL(SUM(r.prompt_tokens + r.completion_tokens),0) AS totalTokens, " +
            "IFNULL(SUM(r.prompt_tokens * #{inputPrice} + r.completion_tokens * #{outputPrice}),0) AS totalCost, " +
            "MAX(r.request_time) AS lastRequestTime " +
            "FROM request_log r LEFT JOIN api_key a ON r.api_key_id = a.id " +
            "GROUP BY r.api_key_id, a.remark, a.key_prefix " +
            "ORDER BY totalRequests DESC")
    List<Map<String, Object>> usageByKey(@Param("inputPrice") double inputPrice, @Param("outputPrice") double outputPrice);
}
