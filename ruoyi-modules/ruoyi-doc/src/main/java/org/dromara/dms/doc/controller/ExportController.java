package org.dromara.dms.doc.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocExportTask;
import org.dromara.dms.doc.dto.ZipSelectionRequest;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.ExportTaskService;
import org.dromara.dms.doc.service.PermissionService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步打包下载 API
 *
 * <p>体量大的目录走这里：提交后立刻返回任务，后台生成 ZIP，完成后在任务列表里下载。
 * 相比同步流式打包，优点是**有真实进度**（后台按文件数推进并落库）、
 * 不受 HTTP 长连接时长限制；代价是要等一会儿才能拿到文件。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/doc/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportTaskService exportTaskService;
    private final PermissionService permissionService;

    /** 提交文件夹异步打包任务 */
    @PostMapping("/folders/{folderId}")
    public R<Map<String, Object>> submitFolder(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.VISIBLE, userId);
        return R.ok(toVo(exportTaskService.submitFolder(folderId, userId)));
    }

    /** 提交多选异步打包任务 */
    @PostMapping("/selection")
    public R<Map<String, Object>> submitSelection(@RequestBody ZipSelectionRequest req) {
        Long userId = LoginHelper.getUserId();
        if (req.getFolderIds() != null) {
            for (Long id : req.getFolderIds()) {
                permissionService.requireFolder(id, PermissionFlag.VISIBLE, userId);
            }
        }
        return R.ok(toVo(exportTaskService.submitSelection(
                req.getFolderIds() == null ? List.of() : req.getFolderIds(),
                req.getFileIds() == null ? List.of() : req.getFileIds(), userId)));
    }

    /** 我的导出任务列表 */
    @GetMapping("/tasks")
    public R<List<Map<String, Object>>> tasks(@RequestParam(defaultValue = "30") int limit) {
        Long userId = LoginHelper.getUserId();
        List<Map<String, Object>> vo = new ArrayList<>();
        for (DocExportTask t : exportTaskService.listMine(userId, limit)) {
            vo.add(toVo(t));
        }
        return R.ok(vo);
    }

    /** 取消任务（仅未开始） */
    @PostMapping("/tasks/{taskId}/cancel")
    public R<Void> cancel(@PathVariable Long taskId) {
        exportTaskService.cancel(taskId, LoginHelper.getUserId());
        return R.ok();
    }

    /** 删除任务（连带删除已生成的 ZIP） */
    @DeleteMapping("/tasks/{taskId}")
    public R<Void> delete(@PathVariable Long taskId) {
        exportTaskService.delete(taskId, LoginHelper.getUserId());
        return R.ok();
    }

    /** 下载已完成的 ZIP */
    @GetMapping("/tasks/{taskId}/download")
    public void download(@PathVariable Long taskId, HttpServletResponse response) throws IOException {
        exportTaskService.download(taskId, LoginHelper.getUserId(), response);
    }

    /**
     * 任务 VO
     *
     * <p>带上 progress 百分比与可读大小，前端直接显示，不必自己算。
     */
    private Map<String, Object> toVo(DocExportTask t) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("taskId", t.getTaskId());
        vo.put("rootName", t.getRootName());
        vo.put("status", t.getStatus());
        vo.put("fileCount", t.getFileCount());
        vo.put("doneFiles", t.getDoneFiles());
        vo.put("totalBytes", t.getTotalBytes());
        vo.put("doneBytes", t.getDoneBytes());
        vo.put("zipBytes", t.getZipBytes());
        vo.put("skippedCount", t.getSkippedCount());
        vo.put("errorMsg", t.getErrorMsg());
        vo.put("createTime", t.getCreateTime());
        vo.put("finishTime", t.getFinishTime());
        vo.put("expireTime", t.getExpireTime());
        vo.put("downloadCount", t.getDownloadCount());
        int total = t.getFileCount() == null ? 0 : t.getFileCount();
        int done = t.getDoneFiles() == null ? 0 : t.getDoneFiles();
        int progress = total <= 0 ? 0 : Math.min(100, (int) Math.round(done * 100.0 / total));
        vo.put("progress", DocExportTask.SUCCESS.equals(t.getStatus()) ? 100 : progress);
        return vo;
    }
}
