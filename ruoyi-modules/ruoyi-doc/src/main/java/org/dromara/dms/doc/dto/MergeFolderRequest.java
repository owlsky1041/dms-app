package org.dromara.dms.doc.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 合并文件夹请求（剪切粘贴遇到同名文件夹时）
 *
 * @author DMS
 */
@Data
public class MergeFolderRequest {

    /** 目标文件夹 ID（已存在的同名文件夹，源文件夹内容并入其中） */
    @NotNull
    private Long destFolderId;
}
