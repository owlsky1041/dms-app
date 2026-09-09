package org.dromara.dms.doc.service;

import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.UploadInfo;

/**
 * tus 上传完成回调（业务侧）
 *
 * <p>tus 1.0.0-3.3 没有自动回调，所以由前端在 tus 上传完成后调
 * {@code POST /api/upload/{uploadId}/complete} 触发本接口：
 *
 * <ol>
 *   <li>从 tus 元数据中拿到 userId / folderId / fileName / fileHash</li>
 *   <li>调用 {@code tusService.getUploadedBytes(uploadId)} 读字节流</li>
 *   <li>计算 SHA-256（如客户端没传）</li>
 *   <li>把文件上传到 MinIO</li>
 *   <li>调用 FileProcessor 异步生成预览版 + 缩略图</li>
 *   <li>写 doc_file 表元数据</li>
 *   <li>删除 tus 临时文件（{@code tusService.deleteUpload(uploadId)}）</li>
 * </ol>
 *
 * @author DMS
 */
public interface UploadCompletionDelegate {

    /**
     * 处理上传完成的业务逻辑
     *
     * @param uploadInfo tus 上传元数据（含 metadata、length、ownerKey）
     * @param service    tus 服务实例，用于读取上传字节 / 删除临时文件
     */
    void onComplete(UploadInfo uploadInfo, TusFileUploadService service);
}
