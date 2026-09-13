package org.dromara.dms.doc.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.domain.DocExportTask;
import org.dromara.dms.doc.mapper.DocExportTaskMapper;
import org.dromara.dms.doc.service.ExportTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步打包任务的调度
 *
 * <h2>为什么用「轮询 + 抢占」而不是 @Async 直接跑</h2>
 * 直接把任务丢给线程池，应用一重启任务就永远卡在 RUNNING，没人知道；
 * 轮询模式天然可恢复：任务只是 PENDING 状态留在库里，重启后继续被捞起来。
 *
 * <p>并发控制用「带状态条件的 UPDATE」抢占（见 {@code markRunning}），
 * 多条调度线程同时跑也只有一个能抢到，不需要分布式锁。
 *
 * @author DMS
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportTaskJob {

    private final DocExportTaskMapper taskMapper;
    private final ExportTaskService exportTaskService;

    /** 同时打包的任务数上限 */
    @Value("${dms.download.zip.async-concurrency:2}")
    private int concurrency;

    /** 判定 RUNNING 卡死的阈值（分钟）：超过则重新排队 */
    @Value("${dms.download.zip.async-stale-minutes:30}")
    private int staleMinutes;

    /** 当前真正在跑的任务数，用于控制入队 */
    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * 打包线程池
     *
     * <p>固定大小：打包是 IO 密集 + 单线程写 ZIP，开太多反而互相抢带宽。
     * 入队前已用 inFlight 控制总量，所以队列不会积压。
     */
    private final ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 4),
            r -> {
                Thread t = new Thread(r, "dms-export-" + System.nanoTime() % 1000);
                t.setDaemon(true);
                return t;
            });

    /** 捞取待执行任务并派发 */
    @Scheduled(fixedDelayString = "${dms.download.zip.async-poll-ms:3000}")
    public void dispatchPending() {
        int capacity = concurrency - inFlight.get();
        if (capacity <= 0) {
            return;
        }
        List<DocExportTask> pending;
        try {
            pending = taskMapper.selectPending(capacity);
        } catch (Exception e) {
            log.error("扫描待打包任务失败", e);
            return;
        }
        for (DocExportTask task : pending) {
            // 抢占：只有把 PENDING 成功改成 RUNNING 的线程才负责执行
            if (taskMapper.markRunning(task.getTaskId(), LocalDateTime.now()) == 0) {
                continue;
            }
            inFlight.incrementAndGet();
            Long taskId = task.getTaskId();
            pool.execute(() -> {
                try {
                    exportTaskService.runPackage(taskId);
                } catch (Throwable t) {
                    // runPackage 内部已兜底，这里再兜一层防止线程池把异常吞掉
                    log.error("打包任务执行异常: taskId={}", taskId, t);
                    try {
                        exportTaskService.markFailed(taskId, "任务执行异常：" + t.getMessage());
                    } catch (Exception ignore) {
                        // 已经失败，忽略
                    }
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    /**
     * 恢复被中断的任务
     *
     * <p>应用重启会让在跑的任务停在 RUNNING，这里把它们重置回 PENDING 重新执行。
     */
    @Scheduled(cron = "${dms.download.zip.async-recover-cron:0 */10 * * * ?}")
    public void recoverStale() {
        try {
            LocalDateTime before = LocalDateTime.now().minusMinutes(staleMinutes);
            int n = taskMapper.requeueStale(before);
            if (n > 0) {
                log.warn("发现 {} 个卡住的打包任务，已重新排队（可能由应用重启或异常退出导致）", n);
            }
        } catch (Exception e) {
            log.error("恢复卡住的打包任务失败", e);
        }
    }

    /** 清理过期成品：删文件、置为 EXPIRED（保留记录便于追溯） */
    @Scheduled(cron = "${dms.download.zip.async-cleanup-cron:0 15 * * * ?}")
    public void cleanupExpired() {
        try {
            List<DocExportTask> expired = taskMapper.selectExpired(LocalDateTime.now(), 200);
            int deleted = 0;
            for (DocExportTask task : expired) {
                if (task.getFilePath() != null && !task.getFilePath().isBlank()) {
                    try {
                        if (Files.deleteIfExists(Paths.get(task.getFilePath()))) {
                            deleted++;
                        }
                    } catch (Exception e) {
                        log.warn("删除过期导出文件失败: taskId={}, path={}",
                                task.getTaskId(), task.getFilePath(), e);
                    }
                }
                taskMapper.updateById(new DocExportTask()
                        .setTaskId(task.getTaskId())
                        .setStatus(DocExportTask.EXPIRED)
                        .setFilePath(null));
            }
            if (!expired.isEmpty()) {
                log.info("导出文件清理：处理 {} 个过期任务，删除 {} 个文件", expired.size(), deleted);
            }
        } catch (Exception e) {
            log.error("清理过期导出文件失败", e);
        }
    }
}
