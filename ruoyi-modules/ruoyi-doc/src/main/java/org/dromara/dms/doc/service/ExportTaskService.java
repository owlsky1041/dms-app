package org.dromara.dms.doc.service;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.dms.doc.domain.DocExportTask;

import java.io.IOException;
import java.util.List;

/**
 * 异步打包下载服务
 *
 * <h2>为什么需要它</h2>
 * 同步流式 ZIP 受两个约束：请求会长时间挂着（nginx/浏览器都可能断），
 * 而且拿不到进度百分比。体量大的目录（几 GB / 上千文件）适合改成后台打包：
 * 提交后立刻拿到任务，后台慢慢打，完成后在「导出任务」里下载，进度可见。
 *
 * <h2>权限怎么保证（关键设计）</h2>
 * 打包计划在<b>提交时</b>（有登录上下文）就按下载位逐文件过滤并冻结成 JSON，
 * 后台线程只按冻结清单搬运数据，<b>不做任何权限判断</b>——
 * 因为后台线程没有 Sa-Token 上下文，在那里算权限会得到空角色、越权或漏权。
 *
 * <p>代价是"权限在提交那一刻的快照"。为堵住这个窗口：下载成品前会用
 * <b>当前</b>权限重算一次计划，如果现在可下载的文件比冻结时少（说明权限被收紧），
 * 就拒绝下载并要求重新导出。
 *
 * @author DMS
 */
public interface ExportTaskService {

    /**
     * 提交异步打包任务（文件夹）
     *
     * @return 任务（含 taskId 与计划信息）
     */
    DocExportTask submitFolder(Long folderId, Long userId);

    /**
     * 提交异步打包任务（多选）
     */
    DocExportTask submitSelection(List<Long> folderIds, List<Long> fileIds, Long userId);

    /**
     * 当前用户的导出任务列表（最新的在前）
     */
    List<DocExportTask> listMine(Long userId, int limit);

    /**
     * 取消任务（仅未开始的可以取消）
     */
    void cancel(Long taskId, Long userId);

    /**
     * 删除任务（同时删掉已生成的 ZIP 文件）
     */
    void delete(Long taskId, Long userId);

    /**
     * 下载已完成的 ZIP
     *
     * <p>会先用当前权限复核，权限被收紧时拒绝下载。
     */
    void download(Long taskId, Long userId, HttpServletResponse response) throws IOException;

    /**
     * 供调度线程调用：把任务标记为失败并记原因
     */
    void markFailed(Long taskId, String reason);

    /**
     * 供调度线程调用：真正的打包动作（在后台线程执行）
     *
     * <p>内部自行把状态推进到 SUCCESS/FAILED，异常不向外抛。
     */
    void runPackage(Long taskId);
}
