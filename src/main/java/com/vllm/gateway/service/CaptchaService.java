package com.vllm.gateway.service;

import com.vllm.gateway.service.CaptchaService;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片验证码：使用 JDK AWT 生成四位字符图片，
 * 答案以 captchaId 为键存于内存（带 TTL 自动清理）。
 */
@Service
public class CaptchaService {

    private static final long TTL_MS = 5 * 60 * 1000L; // 5 分钟有效
    private static final int WIDTH = 130;
    private static final int HEIGHT = 44;
    private static final char[] CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray(); // 去掉易混淆 0/O/1/I/l

    private final Map<String, CaptchaEntry> store = new ConcurrentHashMap<>();

    private static final class CaptchaEntry {
        final String answer;
        final long expireAt;
        CaptchaEntry(String answer) {
            this.answer = answer;
            this.expireAt = System.currentTimeMillis() + TTL_MS;
        }
    }

    /**
     * 生成验证码，返回 Base64 PNG 图片与 id。
     */
    public Map<String, String> generate() {
        String code = randomCode(4);
        String id = UUID.randomUUID().toString().replace("-", "");
        store.put(id, new CaptchaEntry(code));
        return Map.of(
                "captchaId", id,
                "imageBase64", "data:image/png;base64," + Base64.getEncoder().encodeToString(render(code))
        );
    }

    /**
     * 校验验证码，无论成败都移除该条记录（一次性）。
     */
    public boolean verify(String id, String code) {
        if (id == null || code == null) {
            return false;
        }
        CaptchaEntry entry = store.remove(id);
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() > entry.expireAt) {
            return false;
        }
        return code.equalsIgnoreCase(entry.answer);
    }

    private String randomCode(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(CHARS[rnd(CHARS.length)]);
        }
        return sb.toString();
    }

    private byte[] render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 背景
        g.setColor(new Color(245, 247, 250));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // 干扰线
        for (int i = 0; i < 6; i++) {
            g.setColor(randomColor(140, 200));
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine(rnd(WIDTH), rnd(HEIGHT), rnd(WIDTH), rnd(HEIGHT));
        }

        // 字符
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        int x = 12;
        for (char c : code.toCharArray()) {
            g.setColor(randomColor(30, 130));
            double rot = (rnd(50) - 25) * Math.PI / 180.0;
            int y = 12 + HEIGHT / 2 + (rnd(8) - 4);
            g.rotate(rot, x + 12, y);
            g.drawString(String.valueOf(c), x, y);
            g.rotate(-rot, x + 12, y);
            x += 27;
        }

        // 噪点
        for (int i = 0; i < 60; i++) {
            g.setColor(randomColor(120, 220));
            g.fillRect(rnd(WIDTH), rnd(HEIGHT), 1, 1);
        }

        g.dispose();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
    }

    private Color randomColor(int from, int to) {
        return new Color(from + rnd(to - from + 1), from + rnd(to - from + 1), from + rnd(to - from + 1));
    }

    private int rnd(int bound) {
        return bound <= 0 ? 0 : (int) (Math.random() * bound);
    }
}
