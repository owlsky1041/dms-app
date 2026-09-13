package org.dromara.dms.doc.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * tus 上传临时文件清理
 *
 * <p><b>为什么必须有这个任务</b>：上传完成时（{@code UploadCompletionDelegateImpl} 第 5 步）
 * 会主动删掉临时文件，但那只覆盖"走完流程"的上传。以下情况都会留下垃圾：
 * <ul>
 *   <li>用户传一半关掉浏览器 / 断网 —— 客户端再也不会调用完成接口；</li>
 *   <li>前端传完但没来得及调 {@code /complete} 就关页；</li>
 *   <li>完成处理中途失败（例如写 MinIO 报错）。</li>
 * </ul>
 * 单文件上限 10GB，几个这样的残留就够把系统盘吃掉。实测曾出现一个 305MB 的残留
 * 从 9 月 10 日一直躺到 9 月 13 日——因为 {@code withUploadExpirationPeriod(1天)}
 * 只是"登记了过期时间"，<b>没人调用 cleanup()，过期机制根本不会自己跑</b>。
 *
 * <p>所以这里做两件事：
 * <ol>
 *   <li>调用 tus 官方的 {@code cleanup()}：按登记的有效期清掉过期上传与失效锁；</li>
 *   <li>再按目录时间戳兜底扫一遍：清掉超过 {@code stale-hours} 仍未变动的残留目录
 *       （应对 tus 自己没登记到的孤儿目录）。</li>
 * </ol>
 *
 * <p>另：占用超过 {@code warn-bytes} 会打 ERROR 日志——这是留给"日志报警"的触发点，
 * 免得又出现"悄悄堆到几个 G"的情况。
 *
 * @author DMS
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TusCleanupJob {

    private final TusFileUploadService tusFileUploadService;

    @Value("${dms.tus.temp-dir:/var/dms/tus}")
    private String tempDir;

    /** 多久没变动就算残留（小时）。要明显大于单次上传耗时，避免误删正在续传的任务 */
    @Value("${dms.tus.stale-hours:48}")
    private long staleHours;

    /** 临时目录占用告警阈值（字节），默认 5GB */
    @Value("${dms.tus.warn-bytes:5368709120}")
    private long warnBytes;

    /**
     * 每天凌晨 4:20 清理
     *
     * <p>放在回收站清理（3:30）之后，两个任务错开，避免同时抢磁盘 IO。
     */
    @Scheduled(cron = "${dms.tus.cleanup-cron:0 20 4 * * ?}")
    public void cleanupTusTemp() {
        File dir = new File(tempDir);
        if (!dir.isDirectory()) {
            return;
        }
        long before = usedBytes(dir);
        int removedByTus = 0;
        int removedBySweep = 0;

        // 1. tus 官方清理：按登记的过期时间删（含失效的锁文件）
        try {
            tusFileUploadService.cleanup();
            log.info("tus 过期上传清理完成");
        } catch (IOException e) {
            log.error("tus 官方清理失败（继续走目录兜底扫描）", e);
        }

        // 2. 兜底：扫 uploads 下每个子目录，超过 staleHours 没变动的直接删
        File uploads = new File(dir, "uploads");
        File[] children = uploads.listFiles(File::isDirectory);
        if (children != null) {
            long deadline = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(staleHours);
            for (File child : children) {
                long lastTouched = lastModifiedRecursively(child);
                if (lastTouched > 0 && lastTouched < deadline) {
                    if (deleteRecursively(child)) {
                        removedBySweep++;
                    }
                }
            }
        }

        long after = usedBytes(dir);
        log.info("tus 临时目录清理：目录兜底删除 {} 个残留，占用 {} → {}（释放 {}）",
                removedBySweep, human(before), human(after), human(Math.max(0, before - after)));
        if (removedByTus > 0) {
            log.debug("tus 官方清理计数: {}", removedByTus);
        }
        if (after > warnBytes) {
            // 用 ERROR 级别：这类日志通常被接到告警上，正常清理清不掉说明有别的问题
            log.error("tus 临时目录占用 {} 已超过阈值 {}，请检查是否有大文件上传长期失败或磁盘将满",
                    human(after), human(warnBytes));
        }
    }

    /** 目录及其内容中最新的修改时间（作为"是否还在被写入"的判断依据） */
    private long lastModifiedRecursively(File file) {
        long newest = file.lastModified();
        try (Stream<Path> walk = Files.walk(file.toPath(), 4)) {
            newest = walk.mapToLong(p -> p.toFile().lastModified()).max().orElse(newest);
        } catch (IOException e) {
            log.debug("统计目录时间戳失败: {}", file, e);
        }
        return newest;
    }

    private boolean deleteRecursively(File file) {
        try (Stream<Path> walk = Files.walk(file.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除残留文件失败: {}", p, e);
                }
            });
            return !file.exists();
        } catch (IOException e) {
            log.warn("清理残留目录失败: {}", file, e);
            return false;
        }
    }

    /** 目录占用（递归统计，与系统信息页口径一致） */
    private long usedBytes(File dir) {
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException e) {
            return -1;
        }
    }

    private static String human(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f%s", v, units[i]);
    }
}
