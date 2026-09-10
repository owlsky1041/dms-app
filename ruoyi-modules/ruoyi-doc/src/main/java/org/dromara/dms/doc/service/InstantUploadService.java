package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.DocFile;

/**
 * 秒传（SHA-256 去重引用）服务
 *
 * <p>设计文档 3.3.4：文件 SHA-256 已存在则直接引用，不重复上传字节。
 *
 * @author DMS
 */
public interface InstantUploadService {

    /**
     * 按 SHA-256 查找已存在的文件（未删除）
     *
     * @return 命中则返回已有文件记录，否则 null
     */
    DocFile findByHash(String hash);

    /**
     * 在目标文件夹下创建对已有存储对象的引用记录
     *
     * <p>不复制 MinIO 对象，新记录与源记录共用 storage_key。
     *
     * @param existing      已存在的源文件（提供 storage_key / preview_key 等）
     * @param targetFolder  目标文件夹 id
     * @param userId        操作者
     * @param fileName      期望的文件名（为空则沿用源文件名）
     * @return 新建的引用记录
     */
    DocFile createReference(DocFile existing, Long targetFolder, Long userId, String fileName);
}
