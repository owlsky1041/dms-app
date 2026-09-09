package org.dromara.dms.doc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 授权请求
 *
 * @author DMS
 */
@Data
public class GrantPermissionRequest {

    /** 资源类型：folder / file */
    @NotBlank
    private String resourceType;

    /** 资源 ID（文件夹或文件） */
    @NotNull
    private Long resourceId;

    /** 主体类型：user / role / dept */
    @NotBlank
    private String subjectType;

    /** 主体 ID */
    @NotNull
    private Long subjectId;

    /** 权限位掩码（如 8=下载, 255=完全控制） */
    @NotNull
    private Integer permFlags;

    /** 是否继承到子项（仅文件夹有效） */
    private Boolean inheritToChildren = true;

    /** 过期时间（可空=永久） */
    private LocalDateTime expiresAt;
}
