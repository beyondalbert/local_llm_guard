package com.vllm.gateway.controller;

import com.vllm.gateway.service.StatService;
import com.vllm.gateway.util.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/stat")
public class StatController {

    @Autowired
    private StatService statService;

    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard() {
        return R.ok(statService.dashboard());
    }

    @GetMapping("/daily")
    public R<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "7") int days,
                                               @RequestParam(required = false) String apiKeyIds) {
        return R.ok(statService.dailyStats(days, parseIds(apiKeyIds)));
    }

    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String model,
                                         @RequestParam(required = false) Long apiKeyId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String startTime,
                                         @RequestParam(required = false) String endTime) {
        return R.ok(statService.detail(page, size, model, apiKeyId, status, startTime, endTime));
    }

    @GetMapping("/exportCsv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String model,
                                            @RequestParam(required = false) Long apiKeyId,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String startTime,
                                            @RequestParam(required = false) String endTime) {
        byte[] csv = statService.exportCsv(model, apiKeyId, status, startTime, endTime);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=request_logs.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    private List<Long> parseIds(String apiKeyIds) {
        List<Long> ids = new ArrayList<>();
        if (apiKeyIds == null || apiKeyIds.isBlank()) {
            return ids;
        }
        for (String s : apiKeyIds.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                try {
                    ids.add(Long.parseLong(t));
                } catch (NumberFormatException ignored) {
                    // skip invalid id
                }
            }
        }
        return ids;
    }
}
