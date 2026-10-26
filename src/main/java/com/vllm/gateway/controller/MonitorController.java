package com.vllm.gateway.controller;

import com.vllm.gateway.service.MonitorService;
import com.vllm.gateway.util.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/monitor")
public class MonitorController {

    @Autowired
    private MonitorService monitorService;

    /**
     * 系统监测快照：CPU / 内存 / JVM / 磁盘 / GPU。
     */
    @GetMapping
    public R<Map<String, Object>> snapshot() {
        return R.ok(monitorService.snapshot());
    }
}
