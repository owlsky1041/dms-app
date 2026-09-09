package org.dromara.dms.doc.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.service.UploadCompletionDelegate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 上传相关回调接口
 *
 * <p>tus 1.0.0-3.3 没有自动完成回调，客户端在 tus 上传完成后调
 * {@code POST /api/upload/complete}（body: { uploadUrl, fileName }）触发业务落库。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadCallbackController {

    private final TusFileUploadService tusService;
    private final UploadCompletionDelegate completionDelegate;

    /**
     * tus 完成回调（业务处理）—— 前端主入口
     *
     * @param body 含 uploadUrl（Uppy response.uploadURL，完整 tus Location）与 fileName
     */
    @PostMapping("/complete")
    public R<Void> completeUpload(@RequestBody Map<String, String> body) {
        String uploadUrl = body.get("uploadUrl");
        Long userId = LoginHelper.getUserId();
        if (uploadUrl == null || uploadUrl.isBlank()) {
            return R.fail("缺少 uploadUrl");
        }
        String owner = String.valueOf(userId);
        UploadInfo info = null;
        for (String candidate : new String[]{uploadUrl, uploadUrl + "/", extractId(uploadUrl)}) {
            try {
                info = tusService.getUploadInfo(candidate, owner);
                if (info != null) break;
            } catch (Exception ignored) {
            }
        }
        if (info == null) {
            log.warn("tus upload not found: url={}", uploadUrl);
            return R.fail("上传会话不存在或已过期");
        }
        log.info("tus upload complete: url={}, size={}, user={}", uploadUrl, info.getLength(), userId);
        completionDelegate.onComplete(info, tusService, "/api/upload/tus/" + info.getId(), userId);
        return R.ok();
    }

    private String extractId(String url) {
        if (url == null) return url;
        int i = url.lastIndexOf('/');
        return i >= 0 ? url.substring(i + 1) : url;
    }

    /**
     * 兼容旧路径（保留，供测试脚本使用）：{uploadId} 自动拼接完整 URL
     */
    @PostMapping("/{uploadId}/complete")
    public R<Void> completeUploadLegacy(@PathVariable String uploadId) {
        Long userId = LoginHelper.getUserId();
        String owner = String.valueOf(userId);
        UploadInfo info = null;
        // 多形式尝试定位 tus 上传（owner 必须与创建时一致）
        for (String candidate : new String[]{
                "/api/upload/tus/" + uploadId,
                "/api/upload/tus/" + uploadId + "/",
                uploadId
        }) {
            try {
                info = tusService.getUploadInfo(candidate, owner);
                if (info != null) {
                    log.info("[tus-debug] found via '{}'", candidate);
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (info == null) {
            log.warn("[tus-debug] NOT found. id={}, owner={}", uploadId, owner);
            return R.fail("上传会话不存在或已过期");
        }
        completionDelegate.onComplete(info, tusService, "/api/upload/tus/" + uploadId, userId);
        return R.ok();
    }

    /**
     * tus 健康检查
     */
    @GetMapping("/health")
    public R<String> health() {
        return R.ok("ok");
    }
}
