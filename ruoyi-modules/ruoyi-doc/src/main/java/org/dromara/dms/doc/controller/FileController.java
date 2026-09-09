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
import org.dromara.dms.doc.service.FileService;
import org.dromara.dms.doc.service.PermissionChecker;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

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
    private final PermissionChecker permissionChecker;

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
        return R.ok(fileService.getById(fileId, userId));
    }

    /**
     * 重命名
     */
    @Log(title = "文件重命名", businessType = BusinessType.UPDATE)
    @PutMapping("/{fileId}/rename")
    public R<Void> rename(@PathVariable Long fileId, @RequestParam String name) {
        Long userId = LoginHelper.getUserId();
        checkPerm(fileId, userId, PermissionFlag.EDIT);
        fileService.rename(fileId, name, userId);
        return R.ok();
    }

    /**
     * 移动
     */
    @PutMapping("/{fileId}/move")
    public R<Void> move(@PathVariable Long fileId, @RequestParam Long targetFolderId) {
        Long userId = LoginHelper.getUserId();
        checkPerm(fileId, userId, PermissionFlag.EDIT);
        fileService.move(fileId, targetFolderId, userId);
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
        fileService.softDelete(fileId, userId);
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
        fileService.streamContent(fileId, request, response);
    }

    /**
     * 缩略图（PNG/JPG，暂未生成则返回 204）
     */
    @GetMapping("/{fileId}/thumbnail")
    public void thumbnail(@PathVariable Long fileId,
                          HttpServletResponse response) throws IOException {
        Long userId = LoginHelper.getUserId();
        fileService.streamThumbnail(fileId, response);
    }

    /**
     * 权限检查辅助
     */
    private void checkPerm(Long fileId, Long userId, PermissionFlag flag) {
        // v1.0 简化：所有登录用户暂时都能操作，后续接入 PermissionChecker
        // TODO: var roleIds = LoginHelper.getRoleIds(); var deptIds = LoginHelper.getDeptIds();
        //       if (!permissionChecker.hasFilePermission(userId, roleIds, deptIds, fileId, flag)) {
        //           throw new ServiceException("无权限");
        //       }
    }
}
