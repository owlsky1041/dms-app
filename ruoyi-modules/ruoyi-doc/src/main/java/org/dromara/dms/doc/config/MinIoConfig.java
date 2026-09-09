package org.dromara.dms.doc.config;

import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置
 *
 * @author DMS
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "dms.minio")
public class MinIoConfig {

    /** MinIO 服务地址（如 http://127.0.0.1:9000） */
    private String endpoint;

    /** Access Key */
    private String accessKey;

    /** Secret Key */
    private String secretKey;

    /** 默认桶名 */
    private String bucket = "dms-files";

    /** 区域（MinIO 留空） */
    private String region = "";

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .region(region.isBlank() ? null : region)
                .build();
    }
}
