package org.dromara.dms.doc.service;

/**
 * 回收站服务
 *
 * <p>集中处理「永久删除」这件事，保证三件事同时发生：
 * <ol>
 *   <li>删除 doc_file_text 里的全文索引</li>
 *   <li>删除 doc_file / doc_folder 记录</li>
 *   <li>删除 MinIO 上的物理对象 —— <b>仅当没有别的记录还在引用它</b>
 *       （秒传与复制会让多条记录共享同一个对象，否则会把别人的文件删掉）</li>
 * </ol>
 *
 * <p>界面上的「永久删除」「清空回收站」和定时任务都走这里，避免逻辑分散导致
 * 某条路径漏删物理对象（早期实现只删记录，对象会永久残留在 MinIO 里）。
 *
 * @author DMS
 */
public interface RecycleService {

    /**
     * 永久删除单个文件
     *
     * @return 实际从 MinIO 删除的对象数（0 表示对象仍被其它记录引用）
     */
    int purgeFile(Long fileId);

    /**
     * 永久删除文件夹（整棵子树，含其中的文件）
     *
     * @return 实际从 MinIO 删除的对象数
     */
    int purgeFolder(Long folderId);

    /**
     * 清空回收站
     *
     * @param userId       当前用户
     * @param onlyOwn      true=只清空该用户自己的（普通用户）；false=清空全部（超级管理员）
     * @return 清理统计
     */
    CleanupResult emptyRecycleBin(Long userId, boolean onlyOwn);

    /**
     * 解析当前生效的回收站保留天数
     *
     * <p>优先取系统参数 {@code sys.recycle.retentionDays}（「系统参数」页面可改、即时生效），
     * 未配置时回落到 {@code dms.recycle.retention-days}。
     * 界面提示与定时任务都用它，避免「页面显示 7 天、实际按 30 天清理」这种不一致。
     *
     * @return 保留天数，&lt;=0 表示不自动清理
     */
    int resolveRetentionDays();

    /**
     * 清理超过保留期的回收站项（定时任务调用）
     *
     * @param retentionDays 保留天数，<=0 表示不清理
     * @return 清理统计
     */
    CleanupResult cleanupExpired(int retentionDays);

    /**
     * 清理结果
     *
     * @param folders  删除的文件夹数
     * @param files    删除的文件数
     * @param objects  从 MinIO 删除的物理对象数
     */
    record CleanupResult(int folders, int files, int objects) {
        public boolean isEmpty() {
            return folders == 0 && files == 0 && objects == 0;
        }

        @Override
        public String toString() {
            return "文件夹 " + folders + " 个、文件 " + files + " 个、物理对象 " + objects + " 个";
        }
    }
}
