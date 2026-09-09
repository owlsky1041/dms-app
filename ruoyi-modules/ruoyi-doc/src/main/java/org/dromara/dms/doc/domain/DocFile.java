package org.dromara.dms.doc.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件实体
 *
 * <p>对应数据库表 doc_file
 *
 * @author DMS
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("doc_file")
public class DocFile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "file_id", type = IdType.AUTO)
    private Long fileId;

    private Long folderId;
    private String fileName;
    private String fileExtension;
    private Long fileSize;
    private String fileHash;
    private String mimeType;

    private String storageBackend;
    private String storageBucket;
    private String storageKey;
    private String previewKey;
    private String thumbnailKey;

    private Integer pageCount;
    private Integer width;
    private Integer height;
    private Long durationMs;

    private Long creatorId;
    private Long deptId;
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Long updateBy;
    private LocalDateTime deletedAt;

    // ========== 视图层附加字段（不存数据库） ==========

    /** 预览 URL（前端直接拼） */
    @TableField(exist = false)
    private String previewUrl;

    /** 缩略图 URL */
    @TableField(exist = false)
    private String thumbnailUrl;

    /** 下载 URL */
    @TableField(exist = false)
    private String downloadUrl;

    /** 用户对此文件的有效权限位 */
    @TableField(exist = false)
    private Integer userFlags;

    /** 是否在回收站 */
    @TableField(exist = false)
    private Boolean inRecycleBin;
}
