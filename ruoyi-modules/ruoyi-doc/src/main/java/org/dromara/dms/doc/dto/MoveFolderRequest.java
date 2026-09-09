package org.dromara.dms.doc.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 移动文件夹请求
 *
 * @author DMS
 */
@Data
public class MoveFolderRequest {

    @NotNull
    private Long newParentId;
}
