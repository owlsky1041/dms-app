package org.dromara.dms.doc.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.dto.BatchMoveRequest;
import org.dromara.dms.doc.service.FileService;
import org.dromara.dms.doc.enums.AuditAction;
import org.dromara.dms.doc.service.AuditService;
import org.dromara.dms.doc.service.ImageDimensionService;
import org.dromara.dms.doc.service.MediaTokenService;
import org.dromara.dms.doc.service.PermissionService;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件 REST API
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/doc/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MediaTokenService mediaTokenService;
    private final ImageDimensionService imageDimensionService;

    /**
     * 是否记录「预览」审计
     *
     * <p>预览是高频动作（翻一遍图纸就是几十次），全记会让审计表迅速膨胀，
     * 因此默认关闭；需要追溯"谁看过哪些文件"时在配置里打开。
     */
    @Value("${dms.audit.record-preview:false}")
    private boolean recordPreview;

    /**
     * 列出文件夹下文件（分页）
     */
    @GetMapping
    public R<IPage<DocFile>> list(@RequestParam Long folderId,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        Long userId = LoginHelper.getUserId();
        return R.ok(fileService.pageByFolder(folderId, page, size, userId));
    }

    /**
     * 文件详情
     */
    @GetMapping("/{fileId}")
    public R<DocFile> detail(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        checkPerm(fileId, userId, PermissionFlag.VISIBLE);
        return R.ok(fileService.getById(fileId, userId));
    }

    /**
     * 重命名
     */
    @Log(title = "文件重命名", businessType = BusinessType.UPDATE)
    @PutMapping("/{fileId}/rename")
    public R<Void> rename(@PathVariable Long fileId, @RequestParam String name) {
        Long userId = LoginHelper.getUserId();
        // 「编辑」位已取消：重命名/移动需完全控制，或本人是上传者
        permissionService.requireFileManageable(fileId, userId);
        DocFile beforeRename = fileService.getById(fileId, userId);
        fileService.rename(fileId, name, userId);
        auditService.record(AuditAction.RENAME, "FILE", fileId, name,
                java.util.Map.of("oldName", beforeRename.getFileName() == null ? "" : beforeRename.getFileName()));
        return R.ok();
    }

    /**
     * 移动
     */
    @PutMapping("/{fileId}/move")
    public R<Void> move(@PathVariable Long fileId, @RequestParam Long targetFolderId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFileManageable(fileId, userId);
        permissionService.requireFolder(targetFolderId, PermissionFlag.UPLOAD, userId);
        fileService.move(fileId, targetFolderId, userId);
        auditService.record(AuditAction.MOVE, "FILE", fileId, null,
                java.util.Map.of("targetFolderId", String.valueOf(targetFolderId)));
        return R.ok();
    }

    /**
     * 复制到目标文件夹
     */
    @PostMapping("/{fileId}/copy")
    public R<Long> copy(@PathVariable Long fileId, @RequestParam Long targetFolderId) {
        Long userId = LoginHelper.getUserId();
        checkPerm(fileId, userId, PermissionFlag.VISIBLE);
        permissionService.requireFolder(targetFolderId, PermissionFlag.UPLOAD, userId);
        Long newId = fileService.copy(fileId, targetFolderId, userId);
        return R.ok(newId);
    }

    /**
     * 批量移动（粘贴"剪切"的文件）
     */
    @PutMapping("/batch-move")
    public R<Void> batchMove(@RequestBody BatchMoveRequest req) {
        Long userId = LoginHelper.getUserId();
        if (req.getFileIds() != null) {
            for (Long id : req.getFileIds()) {
                permissionService.requireFileManageable(id, userId);
            }
        }
        permissionService.requireFolder(req.getTargetFolderId(), PermissionFlag.UPLOAD, userId);
        fileService.moveBatch(req.getFileIds(), req.getTargetFolderId(), userId);
        auditService.record(AuditAction.MOVE, "FILE", null, null,
                java.util.Map.of("fileIds", String.valueOf(req.getFileIds()),
                        "targetFolderId", String.valueOf(req.getTargetFolderId())));
        return R.ok();
    }

    /**
     * 删除（到回收站）
     */
    @Log(title = "文件删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{fileId}")
    public R<Void> softDelete(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        checkPerm(fileId, userId, PermissionFlag.DELETE);
        DocFile deleted = fileService.getById(fileId, userId);
        fileService.softDelete(fileId, userId);
        auditService.record(AuditAction.DELETE, "FILE", fileId, deleted.getFileName());
        return R.ok();
    }

    /**
     * 全局搜索
     */
    @GetMapping("/search")
    public R<?> search(@RequestParam String keyword,
                       @RequestParam(defaultValue = "20") int limit) {
        Long userId = LoginHelper.getUserId();
        return R.ok(fileService.search(keyword, limit, userId));
    }

    /**
     * 下载原文件（支持 HTTP Range 分段下载）
     *
     * <p>从 MinIO 流式转发，Range 请求返回 206。
     */
    @GetMapping("/{fileId}/download")
    public void download(@PathVariable Long fileId,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        Long userId = LoginHelper.getUserId();
        checkPerm(fileId, userId, PermissionFlag.DOWNLOAD);
        // 审计：下载动作留痕（打包下载在 DownloadController 里单独记）
        DocFile auditTarget = fileService.getById(fileId, userId);
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("fileName", auditTarget.getFileName());
        detail.put("fileSize", auditTarget.getFileSize());
        auditService.record(AuditAction.DOWNLOAD, "FILE", fileId,
                auditTarget.getFileName(), detail);
        fileService.download(fileId, request, response);
    }

    /**
     * 预览（PDF.js 等用的流式 PDF，或图片原图）
     *
     * <p>返回原始字节；浏览器通过 Content-Type 自行渲染。
     * PDF 预览走 /{fileId}/preview 流，PDF.js 在浏览器端渲染。
     */
    @GetMapping("/{fileId}/preview")
    public void preview(@PathVariable Long fileId,
                        HttpServletRequest request,
                        HttpServletResponse response) throws IOException {
        Long userId = LoginHelper.getUserId();
        checkPerm(fileId, userId, PermissionFlag.PREVIEW);
        if (recordPreview) {
            auditService.record(AuditAction.PREVIEW, "FILE", fileId, null);
        }
        fileService.streamContent(fileId, request, response);
    }

    /**
     * 换取图片/视频直链（带短期签名令牌）
     *
     * <p>浏览器的 {@code <img src>} / {@code <video src>} 带不上 Authorization 头，
     * 所以媒体不能直接用 {@code /preview}：那条路径要求登录态，裸访问会被拒，
     * 表现就是图片"加载失败"、视频黑屏。这里按当前登录身份签发一条短期直链。
     *
     * @param fileId 文件 ID
     * @return contentUrl / thumbnailUrl / expiresIn（秒）
     */
    @GetMapping("/{fileId}/media")
    public R<Map<String, Object>> media(@PathVariable Long fileId) {
        Long userId = LoginHelper.getUserId();
        checkPerm(fileId, userId, PermissionFlag.PREVIEW);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("contentUrl", mediaUrl(mediaTokenService.sign(fileId, userId, MediaTokenService.KIND_CONTENT)));
        vo.put("thumbnailUrl", mediaUrl(mediaTokenService.sign(fileId, userId, MediaTokenService.KIND_THUMB)));
        vo.put("expiresIn", mediaTokenService.ttlMillis() / 1000);
        return R.ok(vo);
    }

    private String mediaUrl(String token) {
        return "/api/doc/media/" + token;
    }

    /**
     * 批量换取媒体直链（图片画廊用）
     *
     * <p>PhotoSwipe 打开一个目录的图集时要拿到每一张的地址与宽高，
     * 逐张调用 media 接口会打出十几个请求，这里一次拿回来。
     *
     * <p>没有预览权限的文件<b>直接跳过</b>（不报错）：目录里混着无权查看的文件时，
     * 图集应当只显示能看的那些，而不是整个打不开。
     *
     * @param req 文件 ID 列表
     */
    @PostMapping("/media-batch")
    public R<List<Map<String, Object>>> mediaBatch(@RequestBody MediaBatchRequest req) {
        Long userId = LoginHelper.getUserId();
        List<Long> ids = req == null || req.fileIds() == null ? List.of() : req.fileIds();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || list.size() >= 500) {
                break;
            }
            // 整段都要包住：getById 对不存在的 ID 同样会抛异常，
            // 只包住权限检查的话，图集里混进一个已删除的 ID 就会让整批 500（踩过）
            try {
                // 逐个判权限：无权就跳过，不影响整本图集
                permissionService.requireFile(id, PermissionFlag.PREVIEW, userId);
                // getById(fileId, userId) 会带上该用户对文件的权限判定结果
                DocFile file = fileService.getById(id, userId);
                if (file == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fileId", String.valueOf(id));
                item.put("fileName", file.getFileName());
                item.put("fileSize", file.getFileSize());
                item.put("contentUrl", mediaUrl(mediaTokenService.sign(id, userId, MediaTokenService.KIND_CONTENT)));
                item.put("thumbnailUrl", mediaUrl(mediaTokenService.sign(id, userId, MediaTokenService.KIND_THUMB)));
                // 图片宽高：PhotoSwipe 用它算初始缩放；库里没有就现读一次文件头并写回
                int[] dim = imageDimensionService.resolve(file);
                if (dim != null) {
                    item.put("width", dim[0]);
                    item.put("height", dim[1]);
                }
                list.add(item);
            } catch (Exception e) {
                // 单个文件出问题（不存在 / 无权限 / 元数据读不到）只跳过它
                log.debug("批量换链跳过 fileId={}: {}", id, e.getMessage());
            }
        }
        return R.ok(list);
    }

    /**
     * 批量请求体
     *
     * <p>用 List&lt;Long&gt; 而不是 long[]：RuoYi 的 Jackson 开了
     * FAIL_ON_NULL_FOR_PRIMITIVES，前端传 null 元素时基本类型会直接 400。
     */
    public record MediaBatchRequest(List<Long> fileIds) {
    }

    /**
     * 缩略图（PNG/JPG，暂未生成则返回 204）
     */
    @GetMapping("/{fileId}/thumbnail")
    public void thumbnail(@PathVariable Long fileId,
                          HttpServletResponse response) throws IOException {
        Long userId = LoginHelper.getUserId();
        checkPerm(fileId, userId, PermissionFlag.PREVIEW);
        fileService.streamThumbnail(fileId, response);
    }

    /**
     * 权限检查辅助：无权限时抛异常（superadmin/文件创建者豁免，否则须 flags 含要求位）
     */
    private void checkPerm(Long fileId, Long userId, PermissionFlag flag) {
        // 统一走 PermissionService（超管/所有者豁免逻辑集中在一处）
        permissionService.requireFile(fileId, flag, userId);
    }
}
