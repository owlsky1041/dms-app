package org.dromara.dms.doc.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DMS 定时任务开关
 *
 * <p>RuoYi 的 {@code @EnableScheduling} 只在 SnailJob 客户端启用时才生效
 * （{@code SnailJobConfig} 上有 {@code @ConditionalOnProperty(snail-job.enabled=true)}），
 * 而本项目未接入 SnailJob，所以需要自己开启；否则回收站自动清理这类
 * {@code @Scheduled} 任务会被<b>静默忽略</b>（不报错、不执行，最难排查）。
 *
 * <p><b>这里必须用 {@code @Configuration}，不能用 {@code @AutoConfiguration}</b>：
 * Spring Boot 4.1 的 {@code AutoConfigurationExcludeFilter} 只要看到类上带
 * {@code @AutoConfiguration} 就把它排除出组件扫描（除非它同时登记在
 * {@code META-INF/spring/...AutoConfiguration.imports} 里）。
 * 用错注解的结果是这个类根本不会被加载 —— 与同包的 MinIoConfig / TusConfig 保持一致。
 *
 * @author DMS
 */
@Slf4j
@Configuration
@EnableScheduling
public class DmsSchedulingConfig {

    /** 构造时打一行日志，便于确认调度确实被开启（排查「任务没跑」时很有用） */
    public DmsSchedulingConfig() {
        log.info("DMS 定时任务已开启（@EnableScheduling 生效）");
    }
}
