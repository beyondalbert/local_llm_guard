package com.vllm.gateway.controller;

import com.vllm.gateway.service.AdminService;
import com.vllm.gateway.util.JwtUtil;
import com.vllm.gateway.util.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginRequest request) {
        if (request == null || !adminService.verify(request.username, request.password)) {
            return R.error(401, "用户名或密码错误");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("token", jwtUtil.generateToken(request.username));
        data.put("username", request.username);
        return R.ok(data);
    }

    public static class LoginRequest {
        public String username;
        public String password;
    }

    /**
     * 修改当前登录管理员的密码。
     */
    @PostMapping("/password")
    public R<Void> changePassword(@RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        Object username = httpRequest.getAttribute("adminUsername");
        if (request == null) {
            return R.error(400, "参数不能为空");
        }
        if (username == null) {
            return R.error(401, "登录已过期，请重新登录");
        }
        String error = adminService.changePassword(
                String.valueOf(username), request.oldPassword, request.newPassword);
        if (error != null) {
            return R.error(400, error);
        }
        return R.ok();
    }

    public static class ChangePasswordRequest {
        public String oldPassword;
        public String newPassword;
    }
}
