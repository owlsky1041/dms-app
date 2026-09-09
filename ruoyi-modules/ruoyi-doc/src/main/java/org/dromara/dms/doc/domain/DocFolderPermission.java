package org.dromara.dms.doc.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件夹权限实体
 *
 * @author DMS
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("doc_folder_permission")
public class DocFolderPermission implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "perm_id", type = IdType.AUTO)
    private Long permId;

    private Long folderId;
    private String subjectType;
    private Long subjectId;
    private Integer permFlags;
    private Boolean inheritToChildren;
    private Long grantedBy;
    private LocalDateTime grantedAt;
    private LocalDateTime expiresAt;

    // ========== 视图附加 ==========

    /** 主体名称（用于 UI 显示） */
    private transient String subjectName;
}
