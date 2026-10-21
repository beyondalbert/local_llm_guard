package com.vllm.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.vllm.gateway.entity.AdminInfo;
import com.vllm.gateway.repository.AdminMapper;
import com.vllm.gateway.util.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AdminService {

    @Autowired
    private AdminMapper adminMapper;

    @Value("${admin.username}")
    private String defaultUsername;

    @Value("${admin.password}")
    private String defaultPassword;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public void ensureDefaultAdmin() {
        Long count = adminMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        AdminInfo admin = new AdminInfo();
        admin.setUsername(defaultUsername);
        admin.setPasswordHash(encoder.encode(defaultPassword));
        admin.setCreateTime(TimeUtil.now());
        adminMapper.insert(admin);
    }

    public boolean verify(String username, String rawPassword) {
        AdminInfo admin = adminMapper.selectOne(
                new QueryWrapper<AdminInfo>().eq("username", username));
        if (admin == null) {
            return false;
        }
        return encoder.matches(rawPassword, admin.getPasswordHash());
    }

    /**
     * 修改当前登录管理员的密码。返回 null 表示成功，否则为错误信息。
     */
    public String changePassword(String username, String oldPassword, String newPassword) {
        if (username == null || username.isBlank()) {
            return "未获取到当前登录用户";
        }
        if (oldPassword == null || oldPassword.isEmpty()) {
            return "请输入当前密码";
        }
        if (newPassword == null || newPassword.isEmpty()) {
            return "请输入新密码";
        }
        if (newPassword.length() < 8) {
            return "新密码长度不能少于 8 位";
        }
        if (!newPassword.matches(".*[A-Za-z].*") || !newPassword.matches(".*\\d.*")) {
            return "新密码需同时包含字母和数字";
        }
        if (newPassword.equals(oldPassword)) {
            return "新密码不能与当前密码相同";
        }
        AdminInfo admin = adminMapper.selectOne(
                new QueryWrapper<AdminInfo>().eq("username", username));
        if (admin == null) {
            return "管理员账号不存在";
        }
        if (!encoder.matches(oldPassword, admin.getPasswordHash())) {
            return "当前密码不正确";
        }
        adminMapper.update(null, new UpdateWrapper<AdminInfo>()
                .eq("id", admin.getId())
                .set("password_hash", encoder.encode(newPassword)));
        return null;
    }
}
