package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.DocFilePermission;
import org.dromara.dms.doc.domain.DocFolderPermission;
import org.dromara.dms.doc.dto.GrantPermissionRequest;
import org.dromara.dms.doc.enums.PermissionFlag;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 权限服务接口
 *
 * @author DMS
 */
public interface PermissionService {

    /**
     * 列出文件夹权限
     */
    List<DocFolderPermission> listFolderPermissions(Long folderId, Long operatorId);

    /**
     * 列出文件权限
     */
    List<DocFilePermission> listFilePermissions(Long fileId, Long operatorId);

    /**
     * 授权（folder/file + user/role/dept）
     */
    void grant(GrantPermissionRequest req, Long operatorId);

    /**
     * 撤销
     */
    void revoke(String resourceType, Long resourceId, String subjectType,
                Long subjectId, Long operatorId);

    /**
     * 计算当前用户对资源的有效权限位（含继承、角色、部门合并）
     */
    int computeUserFlags(String resourceType, Long resourceId, Long userId);

    /**
     * 批量计算一组文件的权限位（用于列表/搜索给每个文件带上 userFlags）
     *
     * <p>同一目录下的文件共享目录链求值结果，这里按目录缓存，
     * 避免 N 个文件把整条目录链重复查 N 遍。
     *
     * @param files  文件列表
     * @param userId 当前用户
     * @return fileId → 权限位掩码
     */
    java.util.Map<Long, Integer> computeFileFlagsBatch(
            java.util.List<org.dromara.dms.doc.domain.DocFile> files, Long userId);

    /**
     * 批量计算一组文件夹的权限位（用于列表给每个文件夹带上 userFlags）
     *
     * <p>与文件版同理：父链求值结果可以复用，避免 N 个子目录把整条链重复查 N 遍。
     *
     * @param folders 文件夹列表
     * @param userId  当前用户
     * @return folderId → 权限位掩码
     */
    java.util.Map<Long, Integer> computeFolderFlagsBatch(
            java.util.List<org.dromara.dms.doc.domain.DocFolder> folders, Long userId);

    // ================= 统一校验入口 =================
    // 说明：超级管理员与资源所有者（folder.owner_id / file.creator_id）自动通过。
    // 所有写操作与读操作都必须经过这里，避免出现「列表过滤了但可直接按 id 访问」的不对称。

    /**
     * 列出「对本资源实际生效」的全部授权，含本层与继承自上级目录的
     *
     * <p>权限弹窗必须能看到继承来的授权：授权通常建在文档区或上级目录上，
     * 只列本层会让用户误以为「这个文件夹没有任何权限」。
     *
     * @param resourceType folder / file
     * @param resourceId   资源 ID
     * @param operatorId   操作者（调用方需已通过完全控制校验）
     * @return 每项含 sourceType（direct=本层可撤销 / inherited=继承只读）、
     *         来源文件夹（sourceFolderId/Name/Path）与授权内容
     */
    List<Map<String, Object>> listEffectivePermissions(String resourceType, Long resourceId, Long operatorId);

    /**
     * 校验用户是否具备文件的指定权限位，不具备抛 ServiceException
     */
    void requireFile(Long fileId, PermissionFlag flag, Long userId);

    /**
     * 校验用户是否具备文件夹的指定权限位，不具备抛 ServiceException
     *
     * <p>folderId 为 null 或 0（根层级）时无需校验（新建资源归属创建者本人）。
     */
    void requireFolder(Long folderId, PermissionFlag flag, Long userId);

    /**
     * 批量校验文件夹权限（任一不满足即抛异常）
     */
    void requireFolders(Collection<Long> folderIds, PermissionFlag flag, Long userId);

    /**
     * 是否具备文件权限（不抛异常）
     */
    boolean hasFile(Long fileId, PermissionFlag flag, Long userId);

    /**
     * 是否具备文件夹权限（不抛异常）
     */
    boolean hasFolder(Long folderId, PermissionFlag flag, Long userId);

    /**
     * 是否可管理文件（重命名/移动）
     *
     * <p>规则：具备「完全控制」，或本人是该文件的上传者。
     * 「编辑」位已取消，改由本方法统一判定。
     */
    boolean canManageFile(Long fileId, Long userId);

    /**
     * 校验文件可管理性，不满足抛 ServiceException
     */
    void requireFileManageable(Long fileId, Long userId);

    /**
     * 校验文件夹可管理性（改名/移动），不满足抛 ServiceException
     *
     * <p>规则：具备「编辑」位（或历史的「完全控制」位）。「读写」档包含编辑位，
     * 因此读写档可以改名/移动，但拿不到「分配权限」的能力。
     */
    void requireFolderManageable(Long folderId, Long userId);
}
