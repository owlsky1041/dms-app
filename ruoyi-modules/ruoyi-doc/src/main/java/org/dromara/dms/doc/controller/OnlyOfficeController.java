package org.dromara.dms.doc.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.FileService;
import org.dromara.dms.doc.service.OnlyOfficeService;
import org.dromara.dms.doc.service.PermissionService;
import org.dromara.system.api.model.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * OnlyOffice 在线查看网关
 *
 * <p>OnlyOffice 文档服务的取文档行为发生在**服务端**（它自己去 fetch document.url），
 * 因此不能用需要登录态的接口。这里用「短期 HMAC 令牌」换一次匿名下载：
 * <ol>
 *   <li>前端调 {@code GET /api/onlyoffice/config?fileId=} （需登录 + 预览权限）</li>
 *   <li>后端签发带签名的临时下载地址，交给 OnlyOffice</li>
 *   <li>OnlyOffice 用该地址取文档（无需登录，令牌校验通过即可，且会过期）</li>
 * </ol>
 *
 * <p>{@code /api/onlyoffice/file/**} 已加入 security.excludes 白名单。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/onlyoffice")
@RequiredArgsConstructor
public class OnlyOfficeController {

    private final OnlyOfficeService onlyOfficeService;
    private final PermissionService permissionService;
    private final FileService fileService;

    /**
     * 获取编辑器配置（前端据此初始化 OnlyOffice）
     */
    @GetMapping("/config")
    public R<Map<String, Object>> config(@RequestParam Long fileId) {
        Long userId = LoginHelper.getUserId();
        // 与预览同权限：需具备可见/预览
        permissionService.requireFile(fileId, PermissionFlag.PREVIEW, userId);

        DocFile file = fileService.getById(fileId, userId);
        if (file == null) {
            throw new ServiceException("文件不存在");
        }
        if (!onlyOfficeService.supports(file.getFileExtension())) {
            throw new ServiceException("该格式不支持 OnlyOffice 在线查看");
        }
        return R.ok(onlyOfficeService.buildEditorConfig(file, userId));
    }

    /**
     * 文档内容（供 OnlyOffice 服务端拉取）——令牌校验，无需登录
     */
    @GetMapping("/file/{token}")
    public void document(@PathVariable String token,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        Long fileId = onlyOfficeService.verifyToken(token);
        if (fileId == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid or expired token");
            return;
        }
        // 令牌自带授权语义（由已登录且有预览权限的请求签发），此处直接流式返回
        fileService.download(fileId, request, response);
    }

    /**
     * 水印配置（预览浮层使用；内容可在系统参数中配置）
     */
    @GetMapping("/watermark")
    public R<Map<String, Object>> watermark() {
        LoginUser user = LoginHelper.getLoginUser();
        return R.ok(onlyOfficeService.watermarkConfig(user));
    }
}
