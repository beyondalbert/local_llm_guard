package com.vllm.gateway.controller;

import com.vllm.gateway.service.CaptchaService;
import com.vllm.gateway.util.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/captcha")
public class CaptchaController {

    @Autowired
    private CaptchaService captchaService;

    /**
     * 生成图片验证码，返回 captchaId 与 Base64 PNG 图片。
     */
    @GetMapping("/generate")
    public R<Map<String, String>> generate() {
        return R.ok(captchaService.generate());
    }
}
