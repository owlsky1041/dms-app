package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.DocFile;

/**
 * 文件处理服务接口
 *
 * <p>上传完成后异步处理：
 * <ol>
 *   <li>用 Apache Tika 检测 MIME</li>
 *   <li>根据类型分发到对应处理器</li>
 *   <li>生成预览版（PDF）</li>
 *   <li>生成缩略图（PNG/JPG）</li>
 *   <li>提取文本（用于全文搜索）</li>
 *   <li>写元数据更新</li>
 * </ol>
 *
 * <p>实现必须 @Async，因为上传完成后不希望阻塞 tus 响应。
 *
 * @author DMS
 */
public interface FileProcessor {

    /**
     * 异步处理已上传到 MinIO 的文件
     *
     * @param file doc_file 记录（已 INSERT，未设置预览字段）
     */
    void processAsync(DocFile file);

    /**
     * 同步处理（用于手动触发重试）
     */
    void processSync(DocFile file);
}
