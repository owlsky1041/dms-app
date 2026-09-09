package org.dromara.dms.doc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建文件夹请求
 *
 * @author DMS
 */
@Data
public class CreateFolderRequest {

    @NotNull
    private Long parentId;

    @NotBlank
    @Size(max = 255, message = "文件夹名长度不能超过 255")
    private String name;

    private String description;
}
