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
 * 文件提取文本实体（全文搜索用）
 *
 * @author DMS
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("doc_file_text")
public class DocFileText implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 对应 doc_file.file_id（主键） */
    @TableId(value = "file_id", type = IdType.INPUT)
    private Long fileId;

    /** 提取的纯文本 */
    private String extractText;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
