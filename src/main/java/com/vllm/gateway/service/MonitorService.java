package com.vllm.gateway.service;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统监测：CPU / 内存 / 磁盘 / 网络 / JVM / GPU（nvidia-smi）。
 * 参照 sparkDash 的设备监控维度：GPU 用量/温度/功耗/频率/显存/进程，CPU，内存，磁盘，网络。
 */
@Service
public class MonitorService {

    // 网络差分采样所需的历史状态
    private volatile long prevRxBytes = -1;
    private volatile long prevTxBytes = -1;
    private volatile long prevNetTime = 0;

    // 最近一次抓取的 GPU 进程列表（由 gpuInfo 填充）
    private volatile List<Map<String, Object>> lastGpuProcesses = new ArrayList<>();

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", System.currentTimeMillis());

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        // CPU
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("arch", osBean.getArch());
        cpu.put("cores", osBean.getAvailableProcessors());
        double load = osBean.getSystemLoadAverage();
        cpu.put("load", load < 0 ? -1 : round(load));
        // 相对负载 = 负载/核心数，作为 CPU 使用率近似
        double usage = load >= 0 ? load / Math.max(1, osBean.getAvailableProcessors()) * 100 : 0;
        cpu.put("usage", round(Math.min(100, Math.max(0, usage))));
        cpu.put("processCpu", round(osBean.getProcessCpuLoad() * 100));
        cpu.put("model", cpuModel());      // 型号
        cpu.put("temperature", cpuTemp());  // 温度（可能不可用）
        cpu.put("power", cpuPower());       // 功耗/tdp（可能不可用）
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

        // 磁盘（所有根卷）
        map.put("disks", diskList());
        // 兼容旧的单磁盘字段
        map.put("disk", disksSummary());

        // 网络
        map.put("network", networkInfo());

        map.put("gpus", gpuInfo());
        map.put("gpuProcesses", lastGpuProcesses);
        return map;
    }

    // ─────────────────────────── 磁盘 ───────────────────────────

    private List<Map<String, Object>> diskList() {
        List<Map<String, Object>> disks = new ArrayList<>();
        try {
            for (File root : File.listRoots()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("path", root.getAbsolutePath());
                long total = root.getTotalSpace();
                long usable = root.getUsableSpace();
                long used = total - usable;
                d.put("total", total);
                d.put("used", used);
                d.put("free", usable);
                d.put("usagePercent", total == 0 ? 0 : round(used / (double) total * 100));
                disks.add(d);
            }
            // 没有可列举的卷时，回退到工作目录所在盘
            if (disks.isEmpty()) {
                Map<String, Object> d = new LinkedHashMap<>();
                diskInfo(d, new File(".").getAbsoluteFile());
                disks.add(d);
            }
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("path", "?");
            err.put("error", e.getMessage());
            disks.add(err);
        }
        return disks;
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
        if (filesystemRoot == null) filesystemRoot = new File(".");
        long total = filesystemRoot.getTotalSpace();
        long used = total - filesystemRoot.getUsableSpace();
        disk.put("path", filesystemRoot.getAbsolutePath());
        disk.put("total", total);
        disk.put("used", used);
        disk.put("free", filesystemRoot.getUsableSpace());
        disk.put("usagePercent", total == 0 ? 0 : round(used / (double) total * 100));
    }

    /** 总磁盘用量（全部磁盘聚合成一个条目）。 */
    private Map<String, Object> disksSummary() {
        Map<String, Object> sum = new LinkedHashMap<>();
        long total = 0, used = 0;
        for (Map<String, Object> d : diskList()) {
            total += ((Number) d.getOrDefault("total", 0L)).longValue();
            used += ((Number) d.getOrDefault("used", 0L)).longValue();
        }
        sum.put("total", total);
        sum.put("used", used);
        sum.put("free", total - used);
        sum.put("usagePercent", total == 0 ? 0 : round(used / (double) total * 100));
        sum.put("count", diskList().size());
        return sum;
    }

    // ─────────────────────────── 网络 ───────────────────────────

    /** 通过 PowerShell / 计数器获取各网卡 tx/rx 速率（差分采样）。 */
    private Map<String, Object> networkInfo() {
        Map<String, Object> net = new LinkedHashMap<>();
        Map<String, long[]> ifaces = readNetworkCounters(); // name -> [rxBytes, txBytes]
        if (ifaces.isEmpty()) {
            net.put("available", false);
            return net;
        }
        long now = System.currentTimeMillis();
        long rxTotal = 0, txTotal = 0;
        for (long[] c : ifaces.values()) {
            rxTotal += c[0];
            txTotal += c[1];
        }
        double rxRate = 0, txRate = 0;
        if (prevRxBytes >= 0 && prevNetTime > 0) {
            double dt = (now - prevNetTime) / 1000.0;
            if (dt > 0.3) {
                rxRate = Math.max(0, (rxTotal - prevRxBytes) / dt);
                txRate = Math.max(0, (txTotal - prevTxBytes) / dt);
            }
        }
        prevRxBytes = rxTotal;
        prevTxBytes = txTotal;
        prevNetTime = now;

        net.put("available", true);
        net.put("rxRate", round(rxRate));
        net.put("txRate", round(txRate));
        List<Map<String, Object>> interfaces = new ArrayList<>();
        for (Map.Entry<String, long[]> e : ifaces.entrySet()) {
            Map<String, Object> itf = new LinkedHashMap<>();
            itf.put("name", e.getKey());
            itf.put("rxBytes", e.getValue()[0]);
            itf.put("txBytes", e.getValue()[1]);
            interfaces.add(itf);
        }
        net.put("interfaces", interfaces);
        return net;
    }

    /** 读取各网卡累计收发字节。Windows 下用 PowerShell 计数器，其他系统回退 /proc。 */
    private Map<String, long[]> readNetworkCounters() {
        Map<String, long[]> out = new LinkedHashMap<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                List<String> lines = runCommand(new String[] {
                        "powershell", "-NoProfile", "-Command",
                        "Get-CimInstance Win32_PerfRawData_Tcpip_NetworkInterface | "
                                + "ForEach-Object { Write-Output ($_.Name + '|' + $_.BytesReceivedPersec + '|' + $_.BytesSentPersec) }"
                });
                for (String line : lines) {
                    String[] p = line.split("\\|");
                    if (p.length >= 3) {
                        try {
                            out.put(p[0].trim(), new long[] {
                                    Long.parseLong(p[1].trim()), Long.parseLong(p[2].trim()) });
                        } catch (NumberFormatException ignored) { }
                    }
                }
            } else {
                // Linux: /proc/net/dev
                List<String> lines = runCommand(new String[] { "cat", "/proc/net/dev" });
                Pattern pat = Pattern.compile("^\\s*([^:]+):\\s*(\\d+)\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+");
                for (String line : lines) {
                    Matcher m = pat.matcher(line);
                    if (m.find()) {
                        String name = m.group(1).trim();
                        if (name.equals("lo")) continue;
                        out.put(name, new long[] { Long.parseLong(m.group(2)), Long.parseLong(m.group(3)) });
                    }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ─────────────────────────── CPU 扩展 ───────────────────────────

    private String cpuModelCache = null;
    private String cpuModel() {
        if (cpuModelCache != null) return cpuModelCache;
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                List<String> lines = runCommand(new String[] {
                        "powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance Win32_Processor).Name" });
                if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                    cpuModelCache = lines.get(0).trim();
                    return cpuModelCache;
                }
            } else {
                List<String> l = runCommand(new String[] { "sh", "-c",
                        "grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs" });
                if (!l.isEmpty() && !l.get(0).isBlank()) {
                    cpuModelCache = l.get(0).trim();
                    return cpuModelCache;
                }
            }
        } catch (Exception ignored) { }
        cpuModelCache = "";
        return cpuModelCache;
    }

    private Double cpuTempCache = null;
    private long cpuTempCacheAt = 0;
    private Double cpuTemp() {
        long now = System.currentTimeMillis();
        if (cpuTempCache != null && now - cpuTempCacheAt < 10000) return cpuTempCache;
        String os = System.getProperty("os.name", "").toLowerCase();
        Double t = null;
        try {
            if (os.contains("win")) {
                // 主板传感器：MSAcpi_ThermalZoneTemperature（可能为空）
                List<String> lines = runCommand(new String[] {
                        "powershell", "-NoProfile", "-Command",
                        "Get-CimInstance -Namespace root/wmi MSAcpi_ThermalZoneTemperature "
                                + "-ErrorAction SilentlyContinue | Select -First 1 -ExpandProperty CurrentTemperature" });
                if (!lines.isEmpty()) {
                    double raw = Double.parseDouble(lines.get(0).trim());
                    t = raw / 10.0 - 273.15; // tenths of Kelvin -> C
                }
            } else {
                List<String> l = runCommand(new String[] { "sh", "-c",
                        "cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null" });
                if (!l.isEmpty() && !l.get(0).isBlank()) {
                    t = Long.parseLong(l.get(0).trim()) / 1000.0;
                }
            }
        } catch (Exception ignored) { }
        cpuTempCache = t;
        cpuTempCacheAt = now;
        return t;
    }

    private Double cpuPower() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                // AMD/Intel 移动处理器 TDP 常见值；不保证可得，这里直接返回 null
                return null;
            } else {
                List<String> l = runCommand(new String[] { "sh", "-c",
                        "cat /sys/class/powercap/intel-rapl:0/constraint_0_power_limit_uw 2>/dev/null" });
                if (!l.isEmpty() && !l.get(0).isBlank()) {
                    return round(Long.parseLong(l.get(0).trim()) / 1_000_000.0);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ─────────────────────────── GPU ───────────────────────────

    private List<Map<String, Object>> gpuInfo() {
        List<Map<String, Object>> gpus = new ArrayList<>();
        String nvidiaSmi = findNvidiaSmi();
        if (nvidiaSmi == null) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("available", false);
            note.put("note", "未检测到 NVIDIA 显卡驱动（nvidia-smi 不可用）");
            gpus.add(note);
            return gpus;
        }

        try {
            // 连续采样取峰值：utilization.gpu 是上一采样周期的瞬时值
            int samples = 4;
            int intervalMs = 200;
            Map<String, Map<String, Object>> index = new LinkedHashMap<>();
            Map<String, List<Long>> perGpuUtils = new LinkedHashMap<>();

            for (int s = 0; s < samples; s++) {
                if (s > 0) {
                    try { Thread.sleep(intervalMs); } catch (InterruptedException ignored) { }
                }
                for (String line : runGpuQuery(nvidiaSmi)) {
                    String[] parts = line.split(",");
                    if (parts.length < 7) continue;
                    String name = parts[0].trim();
                    Map<String, Object> acc = index.computeIfAbsent(name, k -> new LinkedHashMap<>());
                    if (!acc.containsKey("temperature")) acc.put("temperature", parseLong(parts, 1));
                    acc.put("memTotal", parseBytesFromMB(parseLong(parts, 3)));
                    acc.put("memUsed", parseBytesFromMB(parseLong(parts, 4)));
                    acc.put("memFree", parseBytesFromMB(parseLong(parts, 5)));
                    acc.put("powerDraw", round(parseLong(parts, 6) / 1000.0)); // mW -> W
                    if (acc.get("powerLimit") == null) acc.put("powerLimit", round(parseLong(parts, 7) / 1000.0));
                    long smMHz = parseLong(parts, 8);
                    Long prevClock = (Long) acc.get("clockSmMHz");
                    if (prevClock == null || smMHz > prevClock) acc.put("clockSmMHz", smMHz);
                    // throttle reason
                    String reason = parts.length > 9 ? parts[9].trim() : "";
                    if ("Active".equalsIgnoreCase(reason)) acc.put("throttled", true);
                    else if (acc.get("throttled") == null) acc.put("throttled", false);
                    long util = parseLong(parts, 2);
                    perGpuUtils.computeIfAbsent(name, k -> new ArrayList<>()).add(util);
                }
            }

            for (Map<String, Object> acc : index.values()) {
                long total = ((Number) acc.getOrDefault("memTotal", 0L)).longValue();
                long used = ((Number) acc.getOrDefault("memUsed", 0L)).longValue();
                acc.put("memUsagePercent", total == 0 ? 0 : round(used / (double) total * 100));
                List<Long> utils = perGpuUtils.get(acc.get("name"));
                long peak = 0, sum = 0;
                if (utils != null) {
                    for (Long u : utils) peak = Math.max(peak, u);
                    for (Long u : utils) sum += u;
                }
                acc.put("utilization", peak);
                acc.put("avgUtilization", (utils == null || utils.isEmpty()) ? 0 : round(sum / (double) utils.size()));
                acc.put("sampleCount", utils == null ? 0 : utils.size());
                acc.put("available", true);
                gpus.add(acc);
            }

            // GPU 上运行的进程（按显存排序）——独立字段，避免混入 gpus[] 列表
            lastGpuProcesses = gpuProcesses(nvidiaSmi);
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

    /** 各 GPU 的进程列表（pid / 进程名 / 显存）。 */
    private List<Map<String, Object>> gpuProcesses(String nvidiaSmi) {
        List<Map<String, Object>> procs = new ArrayList<>();
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(nvidiaSmi);
            cmd.add("--query-compute-apps=pid,process_name,used_memory");
            cmd.add("--format=csv,noheader");
            for (String line : runProcess(cmd)) {
                String[] p = line.split(",");
                if (p.length >= 2) {
                    Map<String, Object> proc = new LinkedHashMap<>();
                    proc.put("pid", parseLong(p, 0));
                    proc.put("name", p.length > 1 ? p[1].trim() : "");
                    proc.put("memBytes", p.length > 2 ? parseBytesFromMB(parseLong(p, 2)) : 0);
                    procs.add(proc);
                }
            }
        } catch (Exception ignored) { }
        return procs;
    }

    private List<String> runGpuQuery(String nvidiaSmi) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(nvidiaSmi);
        cmd.add("--query-gpu=name,temperature.gpu,utilization.gpu,memory.total,memory.used,memory.free,"
                + "power.draw,power.limit,clocks.sm,clocks_event_reasons.sm");
        cmd.add("--format=csv,noheader,nounits");
        return runProcess(cmd);
    }

    private List<String> runProcess(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) lines.add(line.trim());
            }
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return lines;
    }

    private List<String> runCommand(String[] cmd) {
        try {
            return runProcess(java.util.Arrays.asList(cmd));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String findNvidiaSmi() {
        String exe = findExecutable("nvidia-smi");
        if (exe == null) {
            for (String p : new String[] {
                    "C:/Windows/System32/nvidia-smi.exe",
                    "C:/Program Files/NVIDIA Corporation/NVSMI/nvidia-smi.exe" }) {
                if (new File(p).exists()) return p;
            }
        }
        return exe;
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
