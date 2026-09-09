package org.dromara.dms.doc.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量移动请求（剪切粘贴）
 *
 * @author DMS
 */
@Data
public class BatchMoveRequest {

    /** 要移动的文件 ID 列表 */
    private List<Long> fileIds;

    /** 目标文件夹 */
    private Long targetFolderId;
}
