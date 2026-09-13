package org.dromara.dms.doc.dto;

import lombok.Data;

import java.util.List;

/**
 * 多选打包下载请求
 *
 * <p>文件夹会递归打包其子树，单独选中的文件放在 ZIP 根目录。
 *
 * @author DMS
 */
@Data
public class ZipSelectionRequest {

    /** 选中的文件夹（递归打包） */
    private List<Long> folderIds;

    /** 选中的单个文件 */
    private List<Long> fileIds;
}
