package org.dromara.dms.doc.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.dromara.dms.doc.domain.DocFile;

import java.util.List;

/**
 * 文件服务接口
 *
 * @author DMS
 */
public interface FileService {

    /**
     * 分页列出文件夹下文件
     */
    IPage<DocFile> pageByFolder(Long folderId, int page, int size, Long userId);

    /**
     * 文件详情（带权限标记）
     */
    DocFile getById(Long fileId, Long userId);

    /**
     * 重命名
     */
    void rename(Long fileId, String newName, Long userId);

    /**
     * 移动
     */
    void move(Long fileId, Long targetFolderId, Long userId);

    /**
     * 软删除
     */
    void softDelete(Long fileId, Long userId);

    /**
     * 恢复
     */
    void restore(Long fileId, Long userId);

    /**
     * 永久删除
     */
    void hardDelete(Long fileId, Long userId);

    /**
     * 全局搜索（PostgreSQL tsvector + pg_trgm）
     */
    List<DocFile> search(String keyword, int limit, Long userId);
}
