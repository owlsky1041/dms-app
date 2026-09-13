package org.dromara.dms.doc.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.FileService;
import org.dromara.dms.doc.service.MediaTokenService;
import org.dromara.dms.doc.service.PermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 媒体内容直链（图片 / 视频 / 音频 / 缩略图）
 *
 * <p>这条路径在 {@code security.excludes} 里免登录，安全性完全由短期 HMAC 令牌保证：
 * <ul>
 *   <li>令牌里有文件 ID 与签发人，<b>每次访问都重新校验一次预览权限</b>——
 *       权限被收回后，手里那条旧链接立刻失效；</li>
 *   <li>令牌有有效期，泄露出去了也只是短期可用；</li>
 *   <li>令牌只对单个文件、单一类型（内容 / 缩略图）有效，不能横向换文件。</li>
 * </ul>
 *
 * <p>为什么不直接给 {@code /api/doc/files/{id}/preview} 加白名单：那条路径被
 * 前端 axios 正常调用（PDF 流等），一旦免登录就没法区分"带令牌"和"裸访问"了。
 * 单独开一条由令牌兜底的路径，职责更清楚。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/doc/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaTokenService mediaTokenService;
    private final PermissionService permissionService;
    private final FileService fileService;

    /**
     * 按令牌输出媒体内容
     *
     * <p>支持 Range：视频拖动进度条靠它（streamContent 内部已实现 206 分段响应）。
     */
    @GetMapping("/{token}")
    public void content(@PathVariable String token,
                        HttpServletRequest request,
                        HttpServletResponse response) throws IOException {
        MediaTokenService.Payload payload = mediaTokenService.verify(token);
        if (payload == null) {
            throw new ServiceException("预览链接已过期，请刷新页面后重试");
        }
        // 关键：令牌只是"入场券"，权限按当前状态重新判定
        permissionService.requireFile(payload.fileId(), PermissionFlag.PREVIEW, payload.userId());

        if (MediaTokenService.KIND_THUMB.equals(payload.kind())) {
            fileService.streamThumbnail(payload.fileId(), response);
        } else {
            fileService.streamContent(payload.fileId(), request, response);
        }
    }
}
