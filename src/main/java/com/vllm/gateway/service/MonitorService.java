package com.vllm.gateway.service;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 系统监测：CPU / 内存 / 磁盘 / JVM，以及 GPU 信息（通过 nvidia-smi 探测）。
 */
@Service
public class MonitorService {

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", System.currentTimeMillis());

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        // CPU
        Map<String, Object> cpu = new LinkedHashMap<>();
        String name = osBean.getArch();
        cpu.put("arch", name);
        cpu.put("cores", osBean.getAvailableProcessors());
        cpu.put("load", round(osBean.getSystemLoadAverage()));
        cpu.put("processCpu", round(osBean.getProcessCpuLoad() * 100));
        map.put("cpu", cpu);

        // 内存
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("total", osBean.getTotalMemorySize());
        mem.put("used", osBean.getTotalMemorySize() - osBean.getFreeMemorySize());
        mem.put("free", osBean.getFreeMemorySize());
        mem.put("usagePercent", round((osBean.getTotalMemorySize() - osBean.getFreeMemorySize())
                / (double) osBean.getTotalMemorySize() * 100));
        map.put("memory", mem);

        // JVM
        Map<String, Object> jvm = new LinkedHashMap<>();
        long heapMax = Runtime.getRuntime().maxMemory();
        long heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        jvm.put("heapMax", heapMax);
        jvm.put("heapUsed", heapUsed);
        jvm.put("heapUsagePercent", heapMax == 0 ? 0 : round(heapUsed / (double) heapMax * 100));
        jvm.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        map.put("jvm", jvm);

        // 磁盘（当前工作目录所在盘）
        Map<String, Object> disk = new LinkedHashMap<>();
        try {
            diskInfo(disk, new File(".").getAbsoluteFile());
        } catch (Exception e) {
            disk.put("error", e.getMessage());
        }
        map.put("disk", disk);

        map.put("gpus", gpuInfo());
        return map;
    }

    private void diskInfo(Map<String, Object> disk, File path) {
        File filesystemRoot = null;
        File f = path;
        File[] roots = File.listRoots();
        while (f != null) {
            String abs = f.getAbsolutePath();
            for (File r : roots) {
                if (abs.startsWith(r.getAbsolutePath())) {
                    filesystemRoot = r;
                    break;
                }
            }
            if (filesystemRoot != null) break;
            f = f.getParentFile();
        }
        if (filesystemRoot == null) {
            filesystemRoot = new File(".");
        }
        long total = filesystemRoot.getTotalSpace();
        long used = total - filesystemRoot.getUsableSpace();
        disk.put("path", filesystemRoot.getAbsolutePath());
        disk.put("total", total);
        disk.put("used", used);
        disk.put("free", filesystemRoot.getUsableSpace());
        disk.put("usagePercent", total == 0 ? 0 : round(used / (double) total * 100));
    }

    /**
     * 探测 GPU：优先 nvidia-smi（NVIDIA），失败则返回空列表并附带原因。
     */
    private List<Map<String, Object>> gpuInfo() {
        List<Map<String, Object>> gpus = new ArrayList<>();
        String nvidiaSmi = findExecutable("nvidia-smi");
        if (nvidiaSmi == null) {
            // 尝试常见 Windows 安装路径
            for (String p : new String[] {
                    "C:/Windows/System32/nvidia-smi.exe",
                    "C:/Program Files/NVIDIA Corporation/NVSMI/nvidia-smi.exe" }) {
                if (new File(p).exists()) {
                    nvidiaSmi = p;
                    break;
                }
            }
        }
        if (nvidiaSmi == null) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("available", false);
            note.put("note", "未检测到 NVIDIA 显卡驱动（nvidia-smi 不可用）");
            gpus.add(note);
            return gpus;
        }

        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(nvidiaSmi);
            cmd.add("--query-gpu=name,temperature.gpu,utilization.gpu,memory.total,memory.used,memory.free");
            cmd.add("--format=csv,noheader,nounits");
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            List<String> lines = new ArrayList<>();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) lines.add(line.trim());
                }
            }
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
            }
            for (String line : lines) {
                gpus.add(parseGpuLine(line));
            }
            if (gpus.isEmpty()) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("available", false);
                note.put("note", "nvidia-smi 存在但未解析到 GPU");
                gpus.add(note);
            }
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("available", false);
            err.put("note", "GPU 探测失败: " + e.getMessage());
            gpus.add(err);
        }
        return gpus;
    }

    private Map<String, Object> parseGpuLine(String line) {
        String[] parts = line.split(",");
        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("available", true);
        gpu.put("name", parts.length > 0 ? parts[0].trim() : "");
        gpu.put("temperature", parseLong(parts, 1));
        gpu.put("utilization", parseLong(parts, 2));
        gpu.put("memTotal", parseBytesFromMB(parseLong(parts, 3)));
        gpu.put("memUsed", parseBytesFromMB(parseLong(parts, 4)));
        gpu.put("memFree", parseBytesFromMB(parseLong(parts, 5)));
        long total = gpu.get("memTotal") instanceof Long ? (Long) gpu.get("memTotal") : 0;
        long used = gpu.get("memUsed") instanceof Long ? (Long) gpu.get("memUsed") : 0;
        gpu.put("memUsagePercent", total == 0 ? 0 : round(used / (double) total * 100));
        return gpu;
    }

    private long parseLong(String[] parts, int idx) {
        if (parts == null || parts.length <= idx) return 0;
        try {
            return Long.parseLong(parts[idx].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseBytesFromMB(long mb) {
        return mb * 1024L * 1024L;
    }

    private String findExecutable(String name) {
        String pathEnv = System.getenv("PATH");
        String[] dirs = pathEnv == null ? new String[0] : pathEnv.split(File.pathSeparator);
        String winName = name + ".exe";
        for (String dir : dirs) {
            if (dir == null || dir.isEmpty()) continue;
            File f = new File(dir, winName);
            if (f.exists()) return f.getAbsolutePath();
            File f2 = new File(dir, name);
            if (f2.exists()) return f2.getAbsolutePath();
        }
        return null;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
