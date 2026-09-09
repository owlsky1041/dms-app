package org.dromara.dms.doc.config;

import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * tus-java-server 配置（1.0.0-3.3 旧 API）
 *
 * <p>关键点：
 * <ul>
 *   <li>{@code withUploadUri("/api/upload/tus")} tus 标准协议路径</li>
 *   <li>{@code withStoragePath} 分块存储目录（建议独立 SSD 分区）</li>
 *   <li>{@code withUploadExpirationPeriod} 过期清理（24 小时）</li>
 *   <li>{@code withThreadLocalCache(true)} 减少磁盘 I/O</li>
 * </ul>
 *
 * <p>1.0.0-3.3 没有 {@code withUploadCompletionListener}（2.0 才有），
 * 所以完成回调由前端在 tus 上传完成后显式调用 {@code POST /api/upload/{uploadId}/complete}。
 * 见 {@link org.dromara.dms.doc.controller.UploadCallbackController}。
 *
 * @author DMS
 */
@Slf4j
@Configuration
public class TusConfig {

    @Value("${dms.tus.upload-uri:/api/upload/tus}")
    private String uploadUri;

    @Value("${dms.tus.temp-dir:/var/dms/tus}")
    private String tempDir;

    @Value("${dms.upload.max-size:10737418240}")  // 默认 10GB
    private long maxUploadSize;

    @Bean
    public TusFileUploadService tusFileUploadService() {
        File dir = new File(tempDir);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Failed to create tus temp dir: {}, using /tmp/dms-tus", tempDir);
            dir = new File("/tmp/dms-tus");
            dir.mkdirs();
        }

        return new TusFileUploadService()
                .withUploadUri(uploadUri)
                .withStoragePath(dir.getAbsolutePath())
                .withMaxUploadSize(maxUploadSize)
                .withUploadExpirationPeriod(java.time.Duration.ofDays(1).toMillis())
                .withThreadLocalCache(true);
    }
}
