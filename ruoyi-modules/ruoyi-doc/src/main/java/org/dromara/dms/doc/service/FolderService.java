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
     * 复制文件夹（连同子文件夹与文件一起复制）
     *
     * <p>目标目录已有同名文件夹时：
     * <ul>
     *   <li>{@code merge = false} —— 新建一个文件夹，名字自动避让为「xxx (1)」</li>
     *   <li>{@code merge = true}  —— 把内容并入目标目录下已有的同名文件夹（递归合并，
     *       子层再遇到同名文件夹继续合并；同名文件仍然自动重命名，不覆盖）</li>
     * </ul>
     * 复制出的文件与源文件共享同一个 MinIO 对象（只新增元数据引用，不复制物理数据）。
     *
     * <p>不复制权限：副本按新位置继承父目录权限，避免把源位置的 deny 规则一起搬过去。
     *
     * @param folderId       源文件夹 ID
     * @param targetParentId 目标父文件夹 ID
     * @param merge          是否合并到同名文件夹
     * @param userId         当前用户
     * @return 实际承载内容的文件夹 ID（合并时是已存在的那个）
     */
    Long copy(Long folderId, Long targetParentId, boolean merge, Long userId);

    /**
     * 是否已存在同名子文件夹（前端粘贴前判断是否弹「合并内容」提示）
     */
    boolean hasChildNamed(Long parentId, String name);

    /**
     * 把整个文件夹「合并进」另一个已存在的文件夹（剪切粘贴遇到同名文件夹时）
     *
     * <p>语义是「移动 + 合并」：源文件夹下的文件移入目标（同名文件自动重命名为
     * 「xxx (1).ext」，不覆盖），子文件夹同名则递归合并、不同名则整体移动，
     * 最后源文件夹因为已空而被物理删除（其内容全部保留在目标里）。
     *
     * <p>相比「先删后建」，这样保留了目标文件夹已有的权限设置与位置。
     *
     * @param srcFolderId  源文件夹（会被删除）
     * @param destFolderId 目标文件夹（已存在，内容并入其中）
     * @param userId       当前用户
     */
    void mergeInto(Long srcFolderId, Long destFolderId, Long userId);

    /**
     * 获取文件夹树（懒加载，一次一层）
     */
    List<DocFolder> listChildren(Long parentId, Long userId);

    /**
     * 获取文件夹面包屑路径
     */
    List<DocFolder> getBreadcrumb(Long folderId);

}
