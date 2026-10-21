package com.vllm.gateway.controller;

import com.vllm.gateway.service.ConfigService;
import com.vllm.gateway.util.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/config")
public class ConfigController {

    @Autowired
    private ConfigService configService;

    @GetMapping
    public R<Map<String, Object>> get() {
        return R.ok(configService.getAll());
    }

    @PutMapping
    public R<Void> save(@RequestBody Map<String, Object> config) {
        configService.save(config);
        return R.ok();
    }
}
