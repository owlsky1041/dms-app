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

    /**
     * 支持在线查看的扩展名 → 文档类型（word/cell/slide/pdf/diagram）
     *
     * <p>列表运行时取自文档服务的 {@code /meta/formats}（带缓存，失败时回退到内置基线），
     * 因此文档服务升级后新增的格式无需改代码即可生效。
     */
    Map<String, String> supportedFormats();

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
