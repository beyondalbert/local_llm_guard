package com.vllm.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vllm.gateway.entity.RequestLog;
import com.vllm.gateway.repository.ChatSessionMapper;
import com.vllm.gateway.repository.RequestLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private RequestLogMapper requestLogMapper;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ConfigService configService;

    public Map<String, Object> dashboard() {
        Map<String, Object> map = new HashMap<>();
        long totalPrompt = nvl(requestLogMapper.sumPromptTokens());
        long totalCompletion = nvl(requestLogMapper.sumCompletionTokens());
        map.put("totalRequests", nvl(requestLogMapper.countAll()));
        map.put("totalPromptTokens", totalPrompt);
        map.put("totalCompletionTokens", totalCompletion);
        map.put("totalTokens", totalPrompt + totalCompletion);
        map.put("totalCost", configService.calcCost(totalPrompt, totalCompletion));
        map.put("inputPricePerM", configService.getInputPricePerM());
        map.put("outputPricePerM", configService.getOutputPricePerM());
        map.put("currency", "CNY");
        map.put("totalSessions", nvl(chatSessionMapper.countAll()));

        String todayStart = LocalDate.now().atStartOfDay().format(TIME_FORMAT);
        String tomorrowStart = LocalDate.now().plusDays(1).atStartOfDay().format(TIME_FORMAT);
        map.put("todayRequests", nvl(requestLogMapper.countBetween(todayStart, tomorrowStart)));
        map.put("todayTokens", nvl(requestLogMapper.sumTokensBetween(todayStart, tomorrowStart)));

        long todayPrompt = nvl(requestLogMapper.sumPromptTokensBetween(todayStart, tomorrowStart));
        long todayCompletion = nvl(requestLogMapper.sumCompletionTokensBetween(todayStart, tomorrowStart));
        map.put("todayCost", configService.calcCost(todayPrompt, todayCompletion));

        String sevenDaysAgo = LocalDate.now().minusDays(6).atStartOfDay().format(TIME_FORMAT);
        List<Map<String, Object>> byDay = requestLogMapper.countByDay(sevenDaysAgo);
        Map<String, Long> dayCount = new LinkedHashMap<>();
        for (Map<String, Object> row : byDay) {
            Object day = row.get("day");
            Object cnt = row.get("cnt");
            if (day != null) {
                dayCount.put(String.valueOf(day), cnt == null ? 0L : ((Number) cnt).longValue());
            }
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            String day = LocalDate.now().minusDays(i).format(DATE_FORMAT);
            Map<String, Object> point = new HashMap<>();
            point.put("date", day);
            point.put("count", dayCount.getOrDefault(day, 0L));
            trend.add(point);
        }
        map.put("requestTrend", trend);
        map.put("modelDistribution", requestLogMapper.countByModel());
        map.put("statusDistribution", requestLogMapper.countByStatus());
        map.put("keyUsage", requestLogMapper.usageByKey(perToken(configService.getInputPricePerM()),
                perToken(configService.getOutputPricePerM())));
        return map;
    }

    /** 每百万价格 -> 每个 token 价格 */
    private double perToken(double perM) {
        return perM / 1_000_000.0;
    }

    public List<Map<String, Object>> dailyStats(int days, List<Long> apiKeyIds) {
        if (days <= 0) {
            days = 7;
        }
        LocalDate startDate = LocalDate.now().minusDays(days - 1L);
        String start = startDate.atStartOfDay().format(TIME_FORMAT);
        List<Long> keys = (apiKeyIds == null || apiKeyIds.isEmpty()) ? null : apiKeyIds;
        List<Map<String, Object>> rows = requestLogMapper.dailyStats(start, keys,
                perToken(configService.getInputPricePerM()),
                perToken(configService.getOutputPricePerM()));

        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object day = row.get("day");
            if (day == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", String.valueOf(day));
            item.put("requests", toLong(row.get("requests")));
            item.put("promptTokens", toLong(row.get("promptTokens")));
            item.put("completionTokens", toLong(row.get("completionTokens")));
            item.put("totalTokens", toLong(row.get("totalTokens")));
            item.put("totalCost", toDouble(row.get("totalCost")));
            byDay.put(String.valueOf(day), item);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            String day = LocalDate.now().minusDays(i).format(DATE_FORMAT);
            Map<String, Object> item = byDay.get(day);
            if (item == null) {
                item = new LinkedHashMap<>();
                item.put("date", day);
                item.put("requests", 0L);
                item.put("promptTokens", 0L);
                item.put("completionTokens", 0L);
                item.put("totalTokens", 0L);
                item.put("totalCost", 0.0);
            }
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> detail(int page, int size, String model, Long apiKeyId,
                                      Integer status, String startTime, String endTime) {
        QueryWrapper<RequestLog> wrapper = buildWrapper(model, apiKeyId, status, startTime, endTime);
        wrapper.orderByDesc("request_time");
        Page<RequestLog> result = requestLogMapper.selectPage(new Page<>(page, size), wrapper);
        Map<String, Object> map = new HashMap<>();
        map.put("total", result.getTotal());
        map.put("records", result.getRecords());
        return map;
    }

    public byte[] exportCsv(String model, Long apiKeyId, Integer status,
                            String startTime, String endTime) {
        QueryWrapper<RequestLog> wrapper = buildWrapper(model, apiKeyId, status, startTime, endTime);
        wrapper.orderByDesc("request_time");
        List<RequestLog> logs = requestLogMapper.selectList(wrapper);

        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("ID,API Key ID,Model,Prompt Tokens,Completion Tokens,Request IP,Cost(ms),Status,Error Message,Request Time\n");
        for (RequestLog log : logs) {
            sb.append(csv(log.getId())).append(',')
              .append(csv(log.getApiKeyId())).append(',')
              .append(csv(log.getModel())).append(',')
              .append(csv(log.getPromptTokens())).append(',')
              .append(csv(log.getCompletionTokens())).append(',')
              .append(csv(log.getRequestIp())).append(',')
              .append(csv(log.getCostMs())).append(',')
              .append(csv(log.getStatus())).append(',')
              .append(csv(log.getErrorMsg())).append(',')
              .append(csv(log.getRequestTime())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private QueryWrapper<RequestLog> buildWrapper(String model, Long apiKeyId, Integer status,
                                                  String startTime, String endTime) {
        QueryWrapper<RequestLog> wrapper = new QueryWrapper<>();
        if (model != null && !model.isBlank()) {
            wrapper.eq("model", model);
        }
        if (apiKeyId != null) {
            wrapper.eq("api_key_id", apiKeyId);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        if (startTime != null && !startTime.isBlank()) {
            wrapper.ge("request_time", startTime);
        }
        if (endTime != null && !endTime.isBlank()) {
            wrapper.le("request_time", endTime);
        }
        return wrapper;
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
