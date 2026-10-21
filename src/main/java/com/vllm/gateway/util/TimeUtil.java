package com.vllm.gateway.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtil {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }

    public static String format(LocalDateTime time) {
        return time == null ? null : time.format(FORMATTER);
    }

    public static LocalDateTime parse(String time) {
        return time == null || time.isEmpty() ? null : LocalDateTime.parse(time, FORMATTER);
    }
}
