package org.dromara.dms.doc.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.PermissionService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * tus 协议端点（1.0.0-3.3 API）
 *
 * <p>所有方法（POST/PATCH/HEAD/DELETE/OPTIONS/GET）统一转发给 tus 服务。
 *
 * <p>ownerKey 用当前登录用户 userId 隔离多用户上传会话，
 * 与 {@code POST /api/upload/{uploadId}/complete} 回调里的 ownerKey 保持一致。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/upload/tus")
@RequiredArgsConstructor
public class TusController {

    private final TusFileUploadService tusService;
    private final PermissionService permissionService;

    @RequestMapping(value = {"", "/**"}, method = {
            RequestMethod.POST,
            RequestMethod.PATCH,
            RequestMethod.HEAD,
            RequestMethod.DELETE,
            RequestMethod.OPTIONS,
            RequestMethod.GET
    })
    public void handleTus(HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        Long userId = LoginHelper.getUserId();
        String ownerKey = String.valueOf(userId);

        // 创建上传会话时先校验目标文件夹的「上传」权限，避免无权限用户白传一遍字节。
        // （最终落库前 UploadCompletionDelegate 会再校验一次，含文件夹上传自动建子目录的场景）
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String folderIdStr = parseMetadata(request.getHeader("Upload-Metadata")).get("folderId");
            if (folderIdStr != null && !folderIdStr.isBlank()) {
                long folderId;
                try {
                    folderId = Long.parseLong(folderIdStr.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("folderId 非法: " + folderIdStr);
                }
                if (folderId == 0L) {
                    throw new ServiceException("请在具体文件夹内上传文件（当前为文档根目录）");
                }
                permissionService.requireFolder(folderId, PermissionFlag.UPLOAD, userId);
            }
        }

        // 1.0.0-3.3：process() 返回 void
        tusService.process(request, response, ownerKey);
    }

    /**
     * 解析 tus 的 Upload-Metadata 头（形如 {@code filename <base64>,folderId <base64>}）
     */
    private Map<String, String> parseMetadata(String header) {
        Map<String, String> meta = new HashMap<>();
        if (header == null || header.isBlank()) {
            return meta;
        }
        for (String pair : header.split(",")) {
            String item = pair.trim();
            if (item.isEmpty()) continue;
            int sp = item.indexOf(' ');
            String key = sp > 0 ? item.substring(0, sp) : item;
            String value = "";
            if (sp > 0) {
                String b64 = item.substring(sp + 1).trim();
                if (!b64.isEmpty()) {
                    try {
                        value = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        value = b64;
                    }
                }
            }
            meta.put(key.trim(), value);
        }
        return meta;
    }
}
