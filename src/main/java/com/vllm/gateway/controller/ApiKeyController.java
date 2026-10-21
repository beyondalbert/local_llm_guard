package com.vllm.gateway.controller;

import com.vllm.gateway.service.ApiKeyService;
import com.vllm.gateway.util.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/apikey")
public class ApiKeyController {

    @Autowired
    private ApiKeyService apiKeyService;

    @GetMapping("/list")
    public R<List<Map<String, Object>>> list() {
        return R.ok(apiKeyService.list());
    }

    @PostMapping("/create")
    public R<Map<String, Object>> create(@RequestBody CreateRequest request) {
        return R.ok(apiKeyService.create(
                request == null ? null : request.remark,
                request == null ? null : request.enabled,
                request == null ? null : request.expireDays,
                request == null ? null : request.allowModels));
    }

    @PutMapping("/update")
    public R<Void> update(@RequestBody UpdateRequest request) {
        if (request == null || request.id == null) {
            return R.error(400, "缺少 id");
        }
        apiKeyService.update(request.id, request.remark, request.enabled,
                request.expireTime, request.allowModels);
        return R.ok();
    }

    @DeleteMapping("/delete")
    public R<Void> delete(@RequestParam Long id) {
        apiKeyService.delete(id);
        return R.ok();
    }

    /**
     * 校验一个原始 Key 是否可用（管理台自助排查）。
     */
    @PostMapping("/verify")
    public R<Map<String, Object>> verify(@RequestBody VerifyRequest request) {
        return R.ok(apiKeyService.verify(request == null ? null : request.key));
    }

    public static class VerifyRequest {
        public String key;
    }

    public static class CreateRequest {
        public String remark;
        public Integer enabled;
        public Integer expireDays;
        public String allowModels;
    }

    public static class UpdateRequest {
        public Long id;
        public String remark;
        public Integer enabled;
        public String expireTime;
        public String allowModels;
    }
}
