package org.dromara.dms.doc.service;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.dms.doc.domain.DocFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * ZIP 打包下载服务（流式）
 *
 * <p>把文件夹（或多选项）打包成 ZIP 直接流式写入响应，全程不在内存里持有完整文件。
 *
 * <h2>为什么用 DEFLATED + NO_COMPRESSION，而不是 ZipEntry.STORED</h2>
 * ZIP 的本地文件头写在数据<b>之前</b>，而 STORED 条目必须在头里给出 CRC-32 与大小。
 * 要拿到 CRC-32 就得把文件完整读一遍，这与「不在内存中缓存完整文件」直接冲突：
 * 要么从 MinIO 读两遍（IO 翻倍），要么落盘缓冲（这不叫流式）。
 *
 * <p>因此这里用 {@code setLevel(Deflater.NO_COMPRESSION)}：压缩方法字段是 DEFLATED，
 * 但 deflate 以 level 0 运行，输出的是 stored block —— <b>实际效果就是不压缩</b>，
 * CPU 开销近似为零，唯一代价是每 64KB 多 5 字节块头（约 0.008%）。
 * 解压端行为与 STORED 完全一致。
 *
 * <p>如果将来确实需要字节级的 STORED（例如要给第三方工具做严格校验），
 * 正确做法是给 {@code doc_file} 加一列 {@code crc32}，在上传后处理时顺手算出来
 * （{@code FileProcessorImpl} 本来就会把文件下载到临时目录供 Tika 识别，
 * 那一次遍历顺手算 CRC 成本为零），之后就能用 {@code ZipEntry.STORED} 且仍然保持流式。
 *
 * @author DMS
 */
public interface ZipDownloadService {

    /**
     * 预检：只查数据库、不碰 MinIO，返回将要打包的内容摘要
     *
     * <p>给前端用于「下载前告知体量与范围」，也能在真正开始传输前把
     * 「有多少文件因无下载权限被跳过」讲清楚，避免用户以为文件丢了。
     *
     * @param folderIds 目标文件夹（会递归其子树）
     * @param fileIds   额外单独选中的文件
     * @param userId    当前用户
     * @return 打包计划
     */
    ZipPlan plan(List<Long> folderIds, List<Long> fileIds, Long userId);

    /**
     * 按计划流式写出 ZIP
     *
     * <p>调用方需保证在调用本方法<b>之前</b>完成所有可能失败的校验
     * （权限、上限等），因为一旦开始写响应体就无法再改状态码或返回 JSON 错误。
     *
     * @param plan     打包计划
     * @param userId   当前用户
     * @param response HTTP 响应
     * @return 实际写出结果（用于日志与审计）
     * @throws IOException 响应流写入失败（客户端断开等）
     */
    ZipResult streamZip(ZipPlan plan, Long userId, HttpServletResponse response) throws IOException;

    /**
     * 把 ZIP 写进任意输出流（同步响应与异步落盘共用同一套打包逻辑）
     *
     * <p>这是打包的<b>唯一实现</b>：{@link #streamZip} 只是给 HTTP 响应加好头之后调它，
     * 异步打包则是传一个文件输出流。这样两种模式的行为（权限过滤、目录结构、
     * 失败处理）永远一致，不会各写一份慢慢跑偏。
     *
     * @param plan     打包计划
     * @param userId   当前用户
     * @param out      目标输出流（调用方负责关闭）
     * @param listener 进度回调，可为 null
     * @return 写出结果
     */
    ZipResult writeZip(ZipPlan plan, Long userId, OutputStream out, ProgressListener listener) throws IOException;

    /**
     * 打包进度回调
     *
     * <p>异步打包用它把进度落库，前端就能显示真实百分比——
     * 这正是同步流式模式做不到的（无 Content-Length）。
     */
    interface ProgressListener {

        /**
         * 每写完一个文件（或目录）回调一次
         *
         * @param entryName    刚写出的条目名
         * @param writtenFiles 累计文件数
         * @param writtenBytes 累计字节数
         * @param totalBytes   计划总字节数（用于算百分比）
         */
        void onEntry(String entryName, int writtenFiles, long writtenBytes, long totalBytes);
    }

    /**
     * 打包计划
     *
     * @param rootName    ZIP 内的顶层目录名（取第一个目标文件夹的名字）
     * @param resourceId 作为入口的资源 ID（单个文件夹打包时是文件夹 ID；多选时为 null）
     * @param entries     待写入条目（已按目录 + 文件展开并排好序）
     * @param fileCount   实际会打包的文件数
     * @param totalBytes  实际会打包的字节数（未压缩）
     * @param skippedCount 因无下载权限被跳过的文件数
     * @param overLimit   是否超出配置上限
     * @param limitReason 超限原因（未超限为 null）
     */
    record ZipPlan(String rootName,
                   Long resourceId,
                   List<ZipEntryPlan> entries,
                   int fileCount,
                   long totalBytes,
                   int skippedCount,
                   boolean overLimit,
                   String limitReason) {

        /** 计划内是否有可打包的文件 */
        public boolean hasFiles() {
            return fileCount > 0;
        }
    }

    /**
     * 单个 ZIP 条目
     *
     * @param entryName  ZIP 内路径（UTF-8，正斜杠分隔，不含前导斜杠）
     * @param directory  是否为目录条目
     * @param bucket     MinIO 桶（目录条目为 null）
     * @param storageKey MinIO 对象 key（目录条目为 null）
     * @param fileId     文件 ID（目录条目为 null）
     * @param size       字节数（目录条目为 0）
     */
    record ZipEntryPlan(String entryName,
                        boolean directory,
                        String bucket,
                        String storageKey,
                        Long fileId,
                        long size) {
    }

    /**
     * 实际写出结果
     *
     * @param writtenFiles  成功写入的文件数
     * @param writtenBytes  写入的未压缩字节数
     * @param skippedFiles  因无权限被跳过的文件名
     * @param failedFiles   读取失败 / 内容不完整的文件名
     * @param rootName      ZIP 顶层目录名
     */
    record ZipResult(int writtenFiles,
                     long writtenBytes,
                     List<String> skippedFiles,
                     List<String> failedFiles,
                     String rootName) {
    }

    /**
     * 便捷方法：单个文件夹打包计划
     */
    ZipPlan planFolder(Long folderId, Long userId);

    /**
     * 取文件夹（用于命名 ZIP 与权限校验），不存在或已删除时抛 ServiceException
     */
    DocFolder requireFolder(Long folderId);
}
