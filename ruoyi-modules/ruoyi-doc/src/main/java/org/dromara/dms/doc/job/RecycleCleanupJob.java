package org.dromara.dms.doc.job;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.service.RecycleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站自动清理任务
 *
 * <p>按期物理删除超过保留期的回收站条目（含 MinIO 物理对象）。
 *
 * <p><b>保留天数的取值优先级</b>：
 * <ol>
 *   <li>系统参数 {@code sys.recycle.retentionDays} —— 在「系统管理 → 系统参数」里改，
 *       改完下次执行即生效、不用重启，这是日常调整的入口</li>
 *   <li>配置项 {@code dms.recycle.retention-days} —— 系统参数没配时的兜底</li>
 * </ol>
 * 两者都读不到才用默认 7 天；值为 0 或负数表示<b>不自动清理</b>。
 *
 * <p>执行时间由 {@code dms.recycle.cleanup-cron} 控制（默认每天 3:30）。
 * 当前是单实例部署，不需要分布式锁；若将来多实例，应改为接入调度中心或加分布式锁，
 * 否则多个实例会同时清理同一批数据。
 *
 * @author DMS
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecycleCleanupJob {

    private final RecycleService recycleService;

    @Value("${dms.recycle.cleanup-cron:0 30 3 * * ?}")
    private String cleanupCron;

    /** 启动时把生效配置打出来：任务「没跑」时一眼就能看出是没注册还是 cron 不对 */
    @PostConstruct
    public void init() {
        int days = recycleService.resolveRetentionDays();
        if (days <= 0) {
            log.warn("回收站自动清理未启用（retentionDays={}），回收站条目不会自动删除", days);
        } else {
            log.info("回收站自动清理已注册：保留 {} 天，执行计划 [{}]（保留天数取自系统参数 "
                    + "sys.recycle.retentionDays，未配置时取 dms.recycle.retention-days）",
                    days, cleanupCron);
        }
    }

    /** 默认每天 3:30 执行一次，清理超过保留期的回收站条目 */
    @Scheduled(cron = "${dms.recycle.cleanup-cron:0 30 3 * * ?}")
    public void cleanupExpiredRecycleItems() {
        long start = System.currentTimeMillis();
        int days = recycleService.resolveRetentionDays();
        try {
            RecycleService.CleanupResult result = recycleService.cleanupExpired(days);
            if (result.isEmpty()) {
                log.info("回收站自动清理：没有超过 {} 天的条目", days);
            } else {
                log.info("回收站自动清理：删除 {}，耗时 {} ms", result, System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            // 定时任务里绝不能把异常抛出去，否则后续调度会被取消
            log.error("回收站自动清理失败", e);
        }
    }

}
