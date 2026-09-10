package org.dromara.dms.doc.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.InstantUploadService;
import org.dromara.dms.doc.service.PermissionService;
import org.dromara.dms.doc.service.UploadCompletionDelegate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
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
    private final InstantUploadService instantUploadService;
    private final PermissionService permissionService;

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
     * 秒传检查（设计文档 API：POST /api/upload/check-hash）
     *
     * <p>前端在分块上传前用浏览器算出 SHA-256 调此接口；命中则无需上传字节，
     * 直接调 {@code POST /api/upload/instant} 建引用。
     *
     * @param body 含 hash（SHA-256，小写十六进制）
     * @return { exists, fileId, fileName, fileSize }
     */
    @PostMapping("/check-hash")
    public R<Map<String, Object>> checkHash(@RequestBody Map<String, String> body) {
        String hash = body.get("hash");
        if (hash == null || hash.isBlank()) {
            return R.fail("缺少 hash");
        }
        DocFile existing = instantUploadService.findByHash(hash);
        Map<String, Object> data = new HashMap<>();
        data.put("exists", existing != null);
        if (existing != null) {
            data.put("fileId", existing.getFileId());
            data.put("fileName", existing.getFileName());
            data.put("fileSize", existing.getFileSize());
        }
        return R.ok(data);
    }

    /**
     * 秒传引用：SHA-256 命中时直接在目标文件夹建引用，不传输字节
     *
     * @param body 含 hash、fileName、folderId
     * @return { fileId, instant: true }
     */
    @PostMapping("/instant")
    public R<Map<String, Object>> instant(@RequestBody Map<String, String> body) {
        Long userId = LoginHelper.getUserId();
        String hash = body.get("hash");
        String folderIdStr = body.get("folderId");
        if (hash == null || hash.isBlank() || folderIdStr == null || folderIdStr.isBlank()) {
            return R.fail("缺少 hash 或 folderId");
        }
        DocFile existing = instantUploadService.findByHash(hash);
        if (existing == null) {
            return R.fail("该文件未命中秒传，请走正常上传");
        }
        Long folderId = Long.parseLong(folderIdStr);
        if (folderId == 0L) {
            return R.fail("请在具体文件夹内上传文件（当前为文档根目录）");
        }
        // 秒传引用等价于在目标文件夹上传一个文件
        permissionService.requireFolder(folderId, PermissionFlag.UPLOAD, userId);
        DocFile ref = instantUploadService.createReference(existing, folderId, userId, body.get("fileName"));
        log.info("Instant upload hit: user={}, folder={}, hash={}, newFileId={}",
                userId, folderId, hash, ref.getFileId());
        Map<String, Object> data = new HashMap<>();
        data.put("fileId", ref.getFileId());
        data.put("fileName", ref.getFileName());
        data.put("instant", true);
        return R.ok(data);
    }

    /**
     * tus 健康检查
     */
    @GetMapping("/health")
    public R<String> health() {
        return R.ok("ok");
    }
}
