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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 上传相关回调接口
 *
 * <p>tus 1.0.0-3.3 没有自动完成回调，客户端在 tus 上传完成后调：
 * <ul>
 *   <li>{@code GET /api/upload/tus/{uploadId}}    查询上传进度（返回 Tus-Offset 等头）</li>
 *   <li>{@code POST /api/upload/{uploadId}/complete}    触发业务元数据写入</li>
 *   <li>{@code DELETE /api/upload/tus/{uploadId}}    取消上传</li>
 * </ul>
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
     * tus 完成回调（业务处理）
     *
     * <p>前端在 tus 上传成功后调此接口，触发：
     * <ol>
     *   <li>从 tus 元数据读取 fileName/folderId/userId</li>
     *   <li>把分块合并文件并上传到 MinIO</li>
     *   <li>写 doc_file 表元数据</li>
     *   <li>异步触发 FileProcessor（缩略图/预览/文本提取）</li>
     *   <li>删除 tus 临时分块</li>
     * </ol>
     */
    @PostMapping("/{uploadId}/complete")
    public R<Void> completeUpload(@PathVariable String uploadId) {
        Long userId = LoginHelper.getUserId();
        UploadInfo info;
        try {
            info = tusService.getUploadInfo(uploadId, String.valueOf(userId));
        } catch (IOException | TusException e) {
            log.warn("tus upload not found or expired: id={}, error={}", uploadId, e.getMessage());
            return R.fail("上传会话不存在或已过期");
        }
        log.info("tus upload complete callback: id={}, size={}, user={}",
                uploadId, info.getLength(), userId);
        completionDelegate.onComplete(info, tusService);
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
