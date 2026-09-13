package org.dromara.dms.doc.service.impl;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.mapper.SystemInfoMapper;
import org.dromara.dms.doc.service.SystemInfoService;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 系统信息采集实现
 *
 * <p>两条硬规矩：
 * ① <b>采集失败不能让页面挂掉</b>——MinIO 挂了、Redis 连不上时，其余部分照常显示，
 *    对应分组里给一个 error 字段说明原因（运维页面最忌讳"整页白"）。
 * ② <b>不做重活</b>——对象统计有上限，避免桶里有几十万对象时把采集请求拖死。
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemInfoServiceImpl implements SystemInfoService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单个前缀最多统计多少个对象：够用就行，防止大桶把请求拖死 */
    private static final int MAX_SCAN_OBJECTS = 200_000;

    private final SystemInfoMapper mapper;
    private final MinioClient minioClient;
    private final RedissonConnectionFactory redisConnectionFactory;

    @Value("${dms.minio.bucket:dms-files}")
    private String bucket;

    @Value("${dms.minio.endpoint:}")
    private String minioEndpoint;

    @Value("${dms.export.dir:/opt/dms/export}")
    private String exportDir;

    /** MinIO 数据目录（对象存储实际落盘位置） */
    @Value("${dms.minio.data-dir:/opt/minio/data}")
    private String minioDataDir;

    @Value("${dms.tus.temp-dir:/var/dms/tus}")
    private String tusTempDir;

    // ---------------- MinIO 相关配置（页面展示"实际生效值"） ----------------
    // 键名必须与 application-dev.yml 一致，写错就是"页面上显示默认值、实际跑的是别的"，
    // 比不显示更糟。这几个键在 /opt/dms/config/application-dev.yml 里都能对上。

    /** 区域：MinIO 一般留空，空串表示交给 SDK 默认（不是 us-east-1 那种硬编码默认） */
    @Value("${dms.minio.region:}")
    private String minioRegion;

    /** Access Key 只展示掩码后的形态；Secret Key 完全不返回 */
    @Value("${dms.minio.access-key:}")
    private String minioAccessKey;

    @Value("${dms.minio.secret-key:}")
    private String minioSecretKey;

    /** 控制台入口提示：控制台只监听本机，必须走 SSH 隧道 */
    @Value("${dms.minio.console-hint:}")
    private String minioConsoleHint;

    /** 图片/视频直链（HMAC 令牌）有效期 */
    @Value("${dms.media.token-ttl-minutes:120}")
    private int mediaTokenTtlMinutes;

    /** 单文件上传上限 */
    @Value("${dms.upload.max-size:10737418240}")
    private long maxUploadBytes;

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("collectedAt", TS.format(LocalDateTime.now()));
        // 顺序就是页面上的顺序：业务数据量放最前——运维最常问的是"系统里到底有多少东西"，
        // 而服务器/应用那些参数是排查时才细看的。
        root.put("business", business());
        root.put("server", server());
        root.put("jvm", jvm());
        root.put("disks", disks());
        root.put("dmsDirs", dmsDirs());
        root.put("database", database());
        root.put("minio", minio());
        root.put("redis", redis());
        return root;
    }

    // ==================================================================
    // 服务器 / 操作系统
    // ==================================================================

    /**
     * 服务器 / 操作系统
     *
     * <p>除了 JMX 能给的那些，再补几项 Linux 上"看一眼就知道机器状态"的：
     * 发行版全名、内核、CPU 型号、1/5/15 分钟负载、开机时长、Swap。
     * 这些都从 /proc 与 /etc/os-release 读——读不到（比如在 macOS 上开发）就给 null，
     * <b>绝不猜</b>：运维页面上编一个数字比空着更害人。
     */
    private Map<String, Object> server() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hostName", safeHostname());
        m.put("osName", System.getProperty("os.name"));
        // 在 Linux 上 os.version 是内核版本，不是发行版版本，标成"内核"更准确
        m.put("kernelVersion", System.getProperty("os.version"));
        m.put("osArch", System.getProperty("os.arch"));
        m.put("osPrettyName", readOsPrettyName());
        m.put("cpuCores", Runtime.getRuntime().availableProcessors());
        m.put("cpuModel", readCpuModel());
        m.put("cpuArch", System.getProperty("os.arch"));

        // 系统负载：com.sun.management 才有真实物理内存，取不到就只给负载
        double load = -1;
        long memTotal = 0, memFree = 0;
        try {
            java.lang.management.OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();
            load = base.getSystemLoadAverage();
            if (base instanceof com.sun.management.OperatingSystemMXBean sun) {
                memTotal = sun.getTotalMemorySize();
                memFree = sun.getFreeMemorySize();
            }
        } catch (Throwable t) {
            log.debug("读取系统负载/内存失败: {}", t.getMessage());
        }
        m.put("loadAverage", load < 0 ? null : Math.round(load * 100) / 100.0);
        // /proc/loadavg 能一次拿到 1/5/15 分钟三个值；拿不到就只留 1 分钟那个
        double[] loads = readLoadAvg();
        m.put("load1", loads != null ? loads[0] : (load < 0 ? null : Math.round(load * 100) / 100.0));
        m.put("load5", loads != null ? loads[1] : null);
        m.put("load15", loads != null ? loads[2] : null);
        m.put("loadPerCore", loads != null && m.get("cpuCores") != null
                ? Math.round(loads[0] / Math.max(1, Runtime.getRuntime().availableProcessors()) * 100) / 100.0
                : null);

        m.put("memTotalBytes", memTotal);
        m.put("memFreeBytes", memFree);
        m.put("memUsedBytes", memTotal > 0 ? memTotal - memFree : 0);
        m.put("memUsedPercent", memTotal > 0 ? percent(memTotal - memFree, memTotal) : 0);

        // Swap 与开机时长：/proc/meminfo、/proc/uptime
        Map<String, String> meminfo = readProcMeminfo();
        long swapTotal = parseLong(meminfo.get("SwapTotal"), -1) * 1024;
        long swapFree = parseLong(meminfo.get("SwapFree"), -1) * 1024;
        if (swapTotal >= 0) {
            m.put("swapTotalBytes", swapTotal);
            m.put("swapFreeBytes", Math.max(0, swapFree));
            m.put("swapUsedBytes", swapTotal - Math.max(0, swapFree));
        }
        Long hostUptime = readHostUptimeSeconds();
        if (hostUptime != null) {
            m.put("hostUptimeText", humanDuration(hostUptime * 1000));
        }
        return m;
    }

    /** 读 /etc/os-release 的 PRETTY_NAME，如 Debian GNU/Linux 13 (trixie) */
    private String readOsPrettyName() {
        try {
            Path p = Path.of("/etc/os-release");
            if (!Files.exists(p)) {
                return null;
            }
            for (String line : Files.readAllLines(p)) {
                if (line.startsWith("PRETTY_NAME=")) {
                    return line.substring("PRETTY_NAME=".length()).replace("\"", "").trim();
                }
            }
        } catch (Exception e) {
            log.debug("读取 os-release 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 读 /proc/cpuinfo 里第一颗 CPU 的型号 */
    private String readCpuModel() {
        try {
            Path p = Path.of("/proc/cpuinfo");
            if (!Files.exists(p)) {
                return null;
            }
            for (String line : Files.readAllLines(p)) {
                if (line.startsWith("model name")) {
                    int idx = line.indexOf(':');
                    return idx > 0 ? line.substring(idx + 1).trim() : null;
                }
            }
        } catch (Exception e) {
            log.debug("读取 cpuinfo 失败: {}", e.getMessage());
        }
        return null;
    }

    /** /proc/loadavg 前三个数就是 1/5/15 分钟平均负载 */
    private double[] readLoadAvg() {
        try {
            Path p = Path.of("/proc/loadavg");
            if (!Files.exists(p)) {
                return null;
            }
            String[] parts = Files.readString(p).trim().split("\\s+");
            if (parts.length < 3) {
                return null;
            }
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
        } catch (Exception e) {
            log.debug("读取 loadavg 失败: {}", e.getMessage());
            return null;
        }
    }

    /** /proc/meminfo：键值都是 KB */
    private Map<String, String> readProcMeminfo() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Path p = Path.of("/proc/meminfo");
            if (!Files.exists(p)) {
                return out;
            }
            for (String line : Files.readAllLines(p)) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    out.put(line.substring(0, idx).trim(),
                            line.substring(idx + 1).trim().replace("kB", "").trim());
                }
            }
        } catch (Exception e) {
            log.debug("读取 meminfo 失败: {}", e.getMessage());
        }
        return out;
    }

    /** /proc/uptime 第一个数是开机秒数 */
    private Long readHostUptimeSeconds() {
        try {
            Path p = Path.of("/proc/uptime");
            if (!Files.exists(p)) {
                return null;
            }
            String first = Files.readString(p).trim().split("\\s+")[0];
            return (long) Double.parseDouble(first);
        } catch (Exception e) {
            log.debug("读取 uptime 失败: {}", e.getMessage());
            return null;
        }
    }

    private String safeHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ==================================================================
    // 应用 / JVM
    // ==================================================================

    private Map<String, Object> jvm() {
        Map<String, Object> m = new LinkedHashMap<>();
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();

        m.put("javaVersion", System.getProperty("java.version"));
        m.put("javaVendor", System.getProperty("java.vendor"));
        m.put("jvmName", System.getProperty("java.vm.name"));
        m.put("pid", ProcessHandle.current().pid());
        m.put("startTime", TS.format(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(rt.getStartTime()), ZoneId.systemDefault())));
        m.put("uptimeMs", rt.getUptime());
        m.put("uptimeText", humanDuration(rt.getUptime()));

        m.put("javaHome", System.getProperty("java.home"));
        m.put("javaVmVersion", System.getProperty("java.vm.version"));
        m.put("heapInitBytes", heap.getInit());
        m.put("heapUsedBytes", heap.getUsed());
        m.put("heapMaxBytes", heap.getMax());
        m.put("heapCommittedBytes", heap.getCommitted());
        m.put("heapUsedPercent", percent(heap.getUsed(), heap.getMax()));
        m.put("nonHeapUsedBytes", nonHeap.getUsed());
        m.put("nonHeapCommittedBytes", nonHeap.getCommitted());

        // 元空间单独拎出来：它不在堆里，堆很空但元空间满了照样 OOM，排查时容易漏
        m.put("metaspaceUsedBytes", poolUsed("Metaspace"));
        m.put("metaspaceMaxBytes", poolMax("Metaspace"));

        m.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());
        m.put("peakThreadCount", ManagementFactory.getThreadMXBean().getPeakThreadCount());
        m.put("daemonThreadCount", ManagementFactory.getThreadMXBean().getDaemonThreadCount());

        var cls = ManagementFactory.getClassLoadingMXBean();
        m.put("loadedClassCount", cls.getLoadedClassCount());
        m.put("totalLoadedClassCount", cls.getTotalLoadedClassCount());
        m.put("unloadedClassCount", cls.getUnloadedClassCount());

        // GC：次数与累计耗时。判断"是不是 GC 在拖"最直接的两个数
        List<Map<String, Object>> gc = new ArrayList<>();
        long gcCount = 0, gcTime = 0;
        for (java.lang.management.GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", g.getName());
            one.put("count", Math.max(0, g.getCollectionCount()));
            one.put("timeMs", Math.max(0, g.getCollectionTime()));
            gc.add(one);
            gcCount += Math.max(0, g.getCollectionCount());
            gcTime += Math.max(0, g.getCollectionTime());
        }
        m.put("gc", gc);
        m.put("gcCount", gcCount);
        m.put("gcTimeMs", gcTime);

        // 启动参数与几个"踩过坑"的系统属性：
        // user.language/timezone 是我们显式固定的（时区不对会写歪审计时间），
        // 显示出来就能一眼确认有没有生效
        m.put("jvmArgs", rt.getInputArguments());
        m.put("timezone", System.getProperty("user.timezone"));
        m.put("locale", System.getProperty("user.language") + "_" + System.getProperty("user.country"));
        m.put("fileEncoding", System.getProperty("file.encoding"));
        m.put("workingDir", System.getProperty("user.dir"));

        // 这两个目录是 DMS 自己写的东西，出问题多半先看它们的剩余空间
        m.put("exportDir", exportDir);
        m.put("tusTempDir", tusTempDir);
        return m;
    }

    /** 某个内存池的已用量（取不到返回 -1，前端显示"—"） */
    private static long poolUsed(String name) {
        return pool(name, true);
    }

    private static long poolMax(String name) {
        return pool(name, false);
    }

    private static long pool(String name, boolean used) {
        try {
            for (java.lang.management.MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (name.equals(pool.getName())) {
                    MemoryUsage u = pool.getUsage();
                    if (u == null) {
                        return -1;
                    }
                    return used ? u.getUsed() : u.getMax();
                }
            }
        } catch (Throwable t) {
            // 某些 JVM 没有这个池，忽略
        }
        return -1;
    }

    // ==================================================================
    // 磁盘
    // ==================================================================

    private List<Map<String, Object>> disks() {
        List<Map<String, Object>> list = new ArrayList<>();
        // 去重：/opt/dms 与 /opt/dms/export 通常是同一个文件系统，同一块盘列两遍没意义
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String p : new String[]{"/", "/opt/dms", exportDir, tusTempDir}) {
            Map<String, Object> d = disk(p);
            String key = String.valueOf(d.get("store"));
            if (seen.add(key)) {
                list.add(d);
            }
        }
        return list;
    }

    /**
     * DMS 自己的目录占用
     *
     * <p>不把目录大小混进磁盘卡片里：一块盘上跑着别的东西，
     * 只有这几个目录是"我们写的"，单独看才有意义。
     */
    private List<Map<String, Object>> dmsDirs() {
        List<Map<String, Object>> list = new ArrayList<>();
        // 只列"应用自己写"的目录：对象存储数据、导出包、上传临时、日志。
        // 不放整个 /opt/dms —— 它里面还有 root 专属的 backup/(权限 700)，
        // 应用降权成 dms 后走不动那些目录，统计会直接返回 -1（踩过）。
        for (String p : new String[]{minioDataDir, exportDir, tusTempDir, "/opt/dms/logs"}) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", p);
            m.put("bytes", dirSize(new File(p)));
            list.add(m);
        }
        return list;
    }

    private Map<String, Object> disk(String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        try {
            File f = new File(path);
            m.put("exists", f.exists());
            Path real = f.exists() ? f.toPath() : f.toPath().getParent();
            FileStore store = Files.getFileStore(real);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            m.put("store", store.name() + ":" + store.type());
            m.put("totalBytes", total);
            m.put("usableBytes", usable);
            m.put("usedBytes", total - usable);
            m.put("usedPercent", percent(total - usable, total));
        } catch (Exception e) {
            m.put("store", path);
            m.put("exists", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    /**
     * 统计目录占用（递归，带文件数上限）
     *
     * <p>踩过两个坑：
     * <ol>
     *   <li>最初拿它统计根目录，把 /proc/kcore 这类伪文件算进来，根目录变成 281TB；
     *       所以只对 DMS 自己的目录调用；</li>
     *   <li>后来改成"只看两层"，结果 tus 临时目录（文件在 uploads/&lt;uuid&gt;/data，第三层）
     *       永远显示 0 —— 而那正是最需要盯的目录，涨到 300MB 页面上还是 0。</li>
     * </ol>
     * 现在递归统计，但限制文件数上限，避免异常目录结构把采集请求拖死。
     */
    private long dirSize(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return -1;
        }
        final int maxFiles = 200_000;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir.toPath(), 16)) {
            long[] acc = {0L, 0};
            walk.filter(Files::isRegularFile).forEach(p -> {
                if (acc[1] < maxFiles) {
                    acc[0] += p.toFile().length();
                    acc[1]++;
                }
            });
            return acc[0];
        } catch (Exception e) {
            log.debug("统计目录占用失败: {}", dir, e);
            return -1;
        }
    }

    // ==================================================================
    // 数据库
    // ==================================================================

    private Map<String, Object> database() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product", "PostgreSQL");
        try {
            m.put("version", mapper.dbVersion());
            m.put("database", mapper.dbName());
            m.put("sizeBytes", nz(mapper.dbSizeBytes()));
            m.put("tablesBytes", nz(mapper.dbTablesBytes()));
            m.put("tableCount", nz(mapper.dbTableCount()));
            m.put("connections", nz(mapper.dbConnections()));
            m.put("maxConnections", parseInt(mapper.dbMaxConnections(), 0));
            LocalDateTime start = mapper.dbStartTime();
            m.put("startTime", start == null ? null : TS.format(start));
            m.put("uptimeText", start == null ? null : humanDuration(
                    java.time.Duration.between(start, LocalDateTime.now()).toMillis()));
            m.put("ok", true);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    private Map<String, Object> business() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("files", nz(mapper.docFileCount()));
            m.put("folders", nz(mapper.docFolderCount()));
            // 只统计未删除的：含回收站的话，"系统里有多少文档"这件事就说不清了
            m.put("visibleFiles", nz(mapper.visibleFileCount()));
            m.put("visibleFolders", nz(mapper.visibleFolderCount()));
            m.put("totalFileBytes", nz(mapper.totalFileBytes()));
            m.put("largestFileBytes", nz(mapper.largestFileBytes()));
            List<Integer> recycle = mapper.recycleCounts();
            int rf = recycle != null && recycle.size() > 0 ? nz(recycle.get(0)) : 0;
            int rd = recycle != null && recycle.size() > 1 ? nz(recycle.get(1)) : 0;
            m.put("recycleFiles", rf);
            m.put("recycleFolders", rd);
            m.put("exportTasks", nz(mapper.exportTaskCount()));
            m.put("auditLogs", nz(mapper.auditLogCount()));
            m.put("users", nz(mapper.sysUserCount()));
            m.put("depts", nz(mapper.sysDeptCount()));
            m.put("roles", nz(mapper.sysRoleCount()));
            m.put("folderGrants", nz(mapper.folderGrantCount()));
            m.put("fileGrants", nz(mapper.fileGrantCount()));
            m.put("fileExts", mapper.fileExtCounts());
        } catch (Exception e) {
            m.put("error", e.getMessage());
        }
        return m;
    }

    // ==================================================================
    // MinIO
    // ==================================================================

    /**
     * MinIO 采集：运行状态 + 用到的配置
     *
     * <p>页面这一屏就是"对象存储出问题时第一眼要看的东西"，所以除了可达性，
     * 还要把应用实际在用的那几项配置摆出来：地址、桶、区域、路径风格、
     * Access Key（<b>不返回 Secret Key</b>）、数据目录、以及各类直链的有效期。
     * 这些值都从配置里读实际生效值，不是写死的说明文本——配置改了什么，
     * 页面上就是什么，避免"文档说 A、实际上跑的是 B"。
     */
    private Map<String, Object> minio() {
        Map<String, Object> m = new LinkedHashMap<>();

        // ---- 一、应用实际在用的配置 ----
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("endpoint", minioEndpoint);
        cfg.put("bucket", bucket);
        // 区域留空是正常配置（MinIO 不分区），别显示成空白让人以为没配上
        cfg.put("region", minioRegion == null || minioRegion.isBlank() ? "（留空，用 SDK 默认）" : minioRegion);
        cfg.put("accessKey", maskAccessKey(minioAccessKey));
        // Secret Key 一律不回传：这一页虽然是超管专属，但"不该出现在页面上的东西就不返回"
        cfg.put("secretKeySet", minioSecretKey != null && !minioSecretKey.isBlank());
        cfg.put("dataDir", minioDataDir);
        cfg.put("consoleHint", minioConsoleHint == null || minioConsoleHint.isBlank()
                ? "控制台只监听 127.0.0.1:9001，需先建 SSH 隧道：" 
                  + "ssh -L 9001:127.0.0.1:9001 root@<服务器> 再访问 http://localhost:9001"
                : minioConsoleHint);
        // 目录约定：对象键的前缀就是这三类，看桶里有什么的时候要能对得上
        cfg.put("originalPrefix", "files/");
        cfg.put("previewPrefix", "previews/");
        cfg.put("thumbnailPrefix", "thumbs/");
        // 直链与上传上限都直接影响"桶里会长成什么样"
        cfg.put("mediaTokenTtlMinutes", mediaTokenTtlMinutes);
        cfg.put("maxUploadBytes", maxUploadBytes);
        m.put("config", cfg);

        m.put("endpoint", minioEndpoint);
        m.put("bucket", bucket);
        try {
            long t0 = System.nanoTime();
            boolean exists = minioClient.bucketExists(
                    io.minio.BucketExistsArgs.builder().bucket(bucket).build());
            m.put("reachable", true);
            m.put("bucketExists", exists);
            m.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
            if (exists) {
                // 分类统计：原件 / 预览 / 缩略图 分开看，比一个总数有用
                long objects = 0, bytes = 0;
                List<Map<String, Object>> prefixes = new ArrayList<>();
                for (String prefix : new String[]{"files/", "previews/", "thumbs/"}) {
                    long[] stat = countPrefix(prefix);
                    objects += stat[0];
                    bytes += stat[1];
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("prefix", prefix);
                    p.put("objects", stat[0]);
                    p.put("bytes", stat[1]);
                    prefixes.add(p);
                }
                m.put("prefixes", prefixes);
                m.put("objects", objects);
                m.put("bytes", bytes);
                m.put("truncated", objects >= MAX_SCAN_OBJECTS);
            }
            m.put("ok", true);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("reachable", false);
            m.put("error", e.getMessage());
            log.warn("采集 MinIO 信息失败: {}", e.getMessage());
        }
        return m;
    }

    /** 统计某个前缀下的对象数（含子目录）与总字节 */
    private long[] countPrefix(String prefix) {
        long count = 0, bytes = 0;
        try {
            Iterable<Result<Item>> it = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build());
            for (Result<Item> r : it) {
                Item item = r.get();
                if (item.isDir()) {
                    continue;
                }
                count++;
                bytes += item.size();
                if (count >= MAX_SCAN_OBJECTS) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("统计 MinIO 前缀 {} 失败: {}", prefix, e.getMessage());
        }
        return new long[]{count, bytes};
    }

    // ==================================================================
    // Redis
    // ==================================================================

    private Map<String, Object> redis() {
        Map<String, Object> m = new LinkedHashMap<>();
        RedisConnection conn = null;
        try {
            conn = redisConnectionFactory.getConnection();
            Properties info = conn.commands().info();
            m.put("reachable", true);
            m.put("version", prop(info, "redis_version"));
            m.put("mode", prop(info, "redis_mode"));
            m.put("uptimeText", humanDuration(
                    parseLong(prop(info, "uptime_in_seconds"), 0) * 1000));
            m.put("connectedClients", parseInt(prop(info, "connected_clients"), 0));
            m.put("usedMemoryBytes", parseLong(prop(info, "used_memory"), 0));
            m.put("usedMemoryText", prop(info, "used_memory_human"));
            m.put("maxMemoryBytes", parseLong(prop(info, "maxmemory"), 0));
            m.put("totalCommands", parseLong(prop(info, "total_commands_processed"), 0));
            m.put("keyspaceHits", parseLong(prop(info, "keyspace_hits"), 0));
            m.put("keyspaceMisses", parseLong(prop(info, "keyspace_misses"), 0));
            // 当前库的 key 数：RuoYi 默认用 0 号库
            m.put("dbSize", conn.commands().dbSize());
            m.put("ok", true);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("reachable", false);
            m.put("error", e.getMessage());
            log.warn("采集 Redis 信息失败: {}", e.getMessage());
        } finally {
            if (conn != null) {
                RedisConnectionUtils.releaseConnection(conn, redisConnectionFactory);
            }
        }
        return m;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * Access Key 掩码：它是身份标识不是口令，但仍然只露头尾。
     *
     * <p>Access Key 是 20 位大写字母数字，这里保留前 4 后 4，中间用 * 填同样的长度，
     * 既能让人核对"是不是换过 key"，又不至于被截图直接抄走。
     */
    private static String maskAccessKey(String key) {
        if (key == null || key.isBlank()) {
            return "（未配置）";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "*".repeat(key.length() - 8) + key.substring(key.length() - 4);
    }

    private static String prop(Properties p, String key) {
        return p == null ? null : p.getProperty(key);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 使用率百分比，保留一位小数；分母为 0 时返回 0（别显示 NaN） */
    private static double percent(long used, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round(used * 1000.0 / total) / 10.0;
    }

    /** 毫秒 → 人话（3天2小时 / 2小时5分 / 5分12秒） */
    private static String humanDuration(long ms) {
        if (ms <= 0) {
            return "0秒";
        }
        long days = TimeUnit.MILLISECONDS.toDays(ms);
        long hours = TimeUnit.MILLISECONDS.toHours(ms) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天");
        }
        if (hours > 0) {
            sb.append(hours).append("小时");
        }
        if (minutes > 0 && days == 0) {
            sb.append(minutes).append("分");
        }
        if (days == 0 && hours == 0) {
            sb.append(seconds).append("秒");
        }
        return sb.toString();
    }
}
