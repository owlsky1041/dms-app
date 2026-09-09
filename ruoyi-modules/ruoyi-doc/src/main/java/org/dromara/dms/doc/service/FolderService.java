package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.DocFolder;

import java.util.List;

/**
 * 文件夹服务接口
 *
 * @author DMS
 */
public interface FolderService {

    /**
     * 创建文件夹
     *
     * @param parentId 父文件夹 ID
     * @param name     文件夹名
     * @param userId   当前用户
     */
    DocFolder create(Long parentId, String name, Long userId);

    /**
     * 重命名
     */
    void rename(Long folderId, String newName, Long userId);

    /**
     * 软删除（移入回收站）
     */
    void softDelete(Long folderId, Long userId);

    /**
     * 恢复
     */
    void restore(Long folderId, Long userId);

    /**
     * 永久删除
     */
    void hardDelete(Long folderId, Long userId);

    /**
     * 移动文件夹
     */
    void move(Long folderId, Long newParentId, Long userId);

    /**
     * 获取文件夹树（懒加载，一次一层）
     */
    List<DocFolder> listChildren(Long parentId, Long userId);

    /**
     * 获取文件夹面包屑路径
     */
    List<DocFolder> getBreadcrumb(Long folderId);

    /**
     * 获取用户根文件夹
     */
    DocFolder getOrCreateUserRoot(Long userId);
}
