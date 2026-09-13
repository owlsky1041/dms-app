package org.dromara.dms.doc.dto;

import lombok.Data;

/**
 * 复制文件夹请求
 *
 * @author DMS
 */
@Data
public class CopyFolderRequest {

    /** 目标父文件夹 ID */
    private Long targetParentId;

    /**
     * 目标位置已有同名文件夹时是否合并内容
     *
     * <p>false（默认）= 新建一份，名字自动避让为「xxx (1)」；
     * true = 把内容并入已存在的同名文件夹（递归合并）。
     */
    private Boolean merge;
}
