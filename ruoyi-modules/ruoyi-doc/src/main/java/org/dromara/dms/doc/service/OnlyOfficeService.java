package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.DocFile;
import org.dromara.system.api.model.LoginUser;

import java.util.Map;
import java.util.Set;

/**
 * OnlyOffice 集成服务
 *
 * @author DMS
 */
public interface OnlyOfficeService {

    /** 支持在线查看的扩展名 */
    Set<String> SUPPORTED_EXT = Set.of(
            "doc", "docx", "odt", "rtf", "txt",
            "xls", "xlsx", "ods", "csv",
            "ppt", "pptx", "odp",
            "pdf");

    /**
     * 该扩展名是否可用 OnlyOffice 查看
     */
    boolean supports(String fileExtension);

    /**
     * 构建前端初始化所需的编辑器配置
     *
     * @param file   目标文件
     * @param userId 当前用户（用于签发临时下载令牌）
     */
    Map<String, Object> buildEditorConfig(DocFile file, Long userId);

    /**
     * 校验临时下载令牌
     *
     * @return 通过则返回 fileId，否则 null
     */
    Long verifyToken(String token);

    /**
     * 水印配置（内容取自系统参数，支持 {realName}/{account}/{date}/{time} 占位符）
     */
    Map<String, Object> watermarkConfig(LoginUser user);
}
