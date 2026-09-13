package org.dromara.dms.doc.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.dto.ZipSelectionRequest;
import org.dromara.dms.doc.enums.AuditAction;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.service.AuditService;
import org.dromara.dms.doc.service.PermissionService;
import org.dromara.dms.doc.service.ZipDownloadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 打包下载 API（流式 ZIP）
 *
 * <h2>权限模型</h2>
 * 文件夹入口只要求「可见」（能进到这个目录），<b>具体每个文件逐一按「下载」位过滤</b>。
 * 逐文件判定是必须的：文件级授权可以覆盖从文件夹继承来的授权，只看文件夹会漏判。
 * 也就是说：用户拿到的 ZIP 里永远只有他有权下载的文件，绝不因为"打包"而绕过管控。
 *
 * <h2>为什么先 plan 再 download</h2>
 * 一旦开始写响应体就无法再改状态码或返回 JSON，所以所有可能失败的判断
 * （是否有可下载内容、是否超过体量上限）都在 {@code plan} 阶段完成。
 * 前端也可以先调 plan 把「将打包 N 个文件 / 共 X GB / 有 M 个因权限被跳过」告诉用户。
 *
 * @author DMS
 */
@Slf4j
@RestController
@RequestMapping("/api/doc/download")
@RequiredArgsConstructor
public class DownloadController {

    private final ZipDownloadService zipDownloadService;
    private final PermissionService permissionService;
    private final AuditService auditService;

    /**
     * 超过这两个阈值就建议改用异步导出
     *
     * <p>理由：同步流式打包拿不到进度百分比，且请求会一直挂着；
     * 体量大时后台打包 + 任务列表的体验好得多。
     */
    @Value("${dms.download.zip.async-threshold-bytes:1073741824}")
    private long asyncThresholdBytes;

    @Value("${dms.download.zip.async-threshold-files:500}")
    private int asyncThresholdFiles;

    /**
     * 预检：返回将要打包的内容摘要（不传输任何文件内容）
     *
     * <p>用于前端在下载前提示体量与范围，也用于把「有多少文件因无权限被跳过」讲清楚。
     */
    @GetMapping("/folders/{folderId}/plan")
    public R<Map<String, Object>> planFolder(@PathVariable Long folderId) {
        Long userId = LoginHelper.getUserId();
        // 能进到这个目录才有打包入口；文件级下载权限在 plan 里逐文件判定
        permissionService.requireFolder(folderId, PermissionFlag.VISIBLE, userId);
        return R.ok(toVo(zipDownloadService.planFolder(folderId, userId)));
    }

    /**
     * 打包下载文件夹（流式 ZIP）
     *
     * <p>响应为 chunked 的 ZIPP 流，浏览器会直接触发下载。
     */
    @GetMapping("/folders/{folderId}")
    public void downloadFolder(@PathVariable Long folderId,
                               HttpServletResponse response) throws IOException {
        Long userId = LoginHelper.getUserId();
        permissionService.requireFolder(folderId, PermissionFlag.VISIBLE, userId);
        doZip(zipDownloadService.planFolder(folderId, userId), userId, response);
    }

    /**
     * 多选内容的预检（与文件夹预检同一套逻辑）
     */
    @PostMapping("/selection/plan")
    public R<Map<String, Object>> planSelection(@RequestBody ZipSelectionRequest req) {
        Long userId = LoginHelper.getUserId();
        if (req.getFolderIds() != null) {
            for (Long id : req.getFolderIds()) {
                permissionService.requireFolder(id, PermissionFlag.VISIBLE, userId);
            }
        }
        return R.ok(toVo(zipDownloadService.plan(req.getFolderIds(), req.getFileIds(), userId)));
    }

    /**
     * 打包下载多选内容（文件夹递归 + 单文件平铺）
     */
    @PostMapping("/selection")
    public void downloadSelection(@RequestBody ZipSelectionRequest req,
                                  HttpServletResponse response) throws IOException {
        Long userId = LoginHelper.getUserId();
        // 每个选中的文件夹都要可见（否则等于借打包越权访问）
        if (req.getFolderIds() != null) {
            for (Long id : req.getFolderIds()) {
                permissionService.requireFolder(id, PermissionFlag.VISIBLE, userId);
            }
        }
        ZipDownloadService.ZipPlan plan =
                zipDownloadService.plan(req.getFolderIds(), req.getFileIds(), userId);
        doZip(plan, userId, response);
    }

    /**
     * 统一的「先校验、再流式输出」流程
     */
    private void doZip(ZipDownloadService.ZipPlan plan, Long userId,
                       HttpServletResponse response) throws IOException {
        // ---- 以下判断必须在写响应体之前完成，否则无法再返回 JSON 错误 ----
        if (plan.overLimit()) {
            throw new ServiceException(plan.limitReason());
        }
        if (!plan.hasFiles()) {
            throw new ServiceException("这里没有您可下载的文件（可能需要「下载」权限）");
        }

        log.info("开始打包下载: user={}, 根目录={}, 文件数={}, 原始大小={} 字节, 因权限跳过={} 个",
                LoginHelper.getUsername(), plan.rootName(), plan.fileCount(),
                plan.totalBytes(), plan.skippedCount());

        long start = System.currentTimeMillis();
        ZipDownloadService.ZipResult result = zipDownloadService.streamZip(plan, userId, response);
        long elapsed = System.currentTimeMillis() - start;
        log.info("打包下载完成: user={}, 根目录={}, 写出文件={}, 字节={}, 跳过={}, 不完整={}, 耗时={}ms",
                LoginHelper.getUsername(), result.rootName(), result.writtenFiles(),
                result.writtenBytes(), result.skippedFiles().size(), result.failedFiles().size(),
                elapsed);

        // 审计：批量带走资料是敏感动作，必须留痕。
        // 记在流式输出之后，这样 detail 里能带上"实际写出了多少、跳过了多少"。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rootName", result.rootName());
        detail.put("plannedFiles", plan.fileCount());
        detail.put("plannedBytes", plan.totalBytes());
        detail.put("writtenFiles", result.writtenFiles());
        detail.put("writtenBytes", result.writtenBytes());
        detail.put("skippedByPermission", plan.skippedCount());
        detail.put("readFailed", result.skippedFiles().size());
        detail.put("incomplete", result.failedFiles().size());
        detail.put("elapsedMs", elapsed);
        auditService.record(AuditAction.DOWNLOAD, "FOLDER", plan.resourceId(),
                plan.rootName(), detail);
    }

    /** 计划转前端 VO */
    private Map<String, Object> toVo(ZipDownloadService.ZipPlan plan) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("rootName", plan.rootName());
        vo.put("fileCount", plan.fileCount());
        vo.put("totalBytes", plan.totalBytes());
        vo.put("totalSizeText", humanSize(plan.totalBytes()));
        vo.put("skippedCount", plan.skippedCount());
        vo.put("overLimit", plan.overLimit());
        vo.put("limitReason", plan.limitReason());
        // 建议走异步导出：体量大时同步流式既没有进度条，也容易被长连接超时掐断
        boolean recommendAsync = plan.totalBytes() > asyncThresholdBytes
                || plan.fileCount() > asyncThresholdFiles;
        vo.put("recommendAsync", recommendAsync);
        vo.put("asyncThresholdBytes", asyncThresholdBytes);
        vo.put("asyncThresholdFiles", asyncThresholdFiles);
        return vo;
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, units[i]);
    }
}
