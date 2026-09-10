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
 * 文件权限实体
 *
 * @author DMS
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("doc_file_permission")
public class DocFilePermission implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "perm_id", type = IdType.AUTO)
    private Long permId;

    private Long fileId;
    private String subjectType;
    private Long subjectId;
    private Integer permFlags;
    private Long grantedBy;
    private LocalDateTime grantedAt;
    private LocalDateTime expiresAt;
}
