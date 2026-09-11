package org.dromara.dms.doc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFilePermission;
import org.dromara.dms.doc.domain.DocFolderPermission;
import org.dromara.dms.doc.dto.GrantPermissionRequest;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.dms.doc.enums.PermissionFlag;
import org.dromara.dms.doc.enums.SubjectType;
import org.dromara.dms.doc.mapper.DocFilePermissionMapper;
import org.dromara.dms.doc.mapper.DocFolderPermissionMapper;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.service.PermissionScopeResolver;
import org.dromara.dms.doc.service.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限服务实现
 *
 * <p>权限合并策略（取并集，高权限优先）：
 * <ol>
 *   <li>资源直接权限（doc_file_permission / doc_folder_permission）</li>
 *   <li>父文件夹继承权限（递归向上，inherit_to_children 传递）</li>
 *   <li>主体：user（本人）+ role（全部角色）+ dept（部门及其祖先链）</li>
 * </ol>
 *
 * <p>所有者自动拥有完全控制：owner_id == userId 时返回 255。
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final DocFolderPermissionMapper folderPermMapper;
    private final DocFilePermissionMapper filePermMapper;
    private final DocFolderMapper folderMapper;
    private final DocFileMapper fileMapper;
    private final PermissionScopeResolver scopeResolver;

    @Override
    public List<DocFolderPermission> listFolderPermissions(Long folderId, Long operatorId) {
        return folderPermMapper.listByFolder(folderId);
    }

    @Override
    public List<DocFilePermission> listFilePermissions(Long fileId, Long operatorId) {
        return filePermMapper.listByFile(fileId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grant(GrantPermissionRequest req, Long operatorId) {
        // 校验主体类型合法
        SubjectType.of(req.getSubjectType());
        LocalDateTime now = LocalDateTime.now();

        if ("folder".equalsIgnoreCase(req.getResourceType())) {
            DocFolderPermission p = new DocFolderPermission()
                    .setFolderId(req.getResourceId())
                    .setSubjectType(req.getSubjectType())
                    .setSubjectId(req.getSubjectId())
                    .setPermFlags(req.getPermFlags())
                    .setInheritToChildren(req.getInheritToChildren() != null ? req.getInheritToChildren() : true)
                    .setGrantedBy(operatorId)
                    .setGrantedAt(now)
                    .setExpiresAt(req.getExpiresAt());
            // upsert（folder_id + subject_type + subject_id 唯一）
            DocFolderPermission exist = folderPermMapper.selectOne(new QueryWrapper<DocFolderPermission>()
                    .eq("folder_id", req.getResourceId())
                    .eq("subject_type", req.getSubjectType())
                    .eq("subject_id", req.getSubjectId())
                    .last("LIMIT 1"));
            if (exist != null) {
                exist.setPermFlags(req.getPermFlags());
                exist.setInheritToChildren(p.getInheritToChildren());
                exist.setExpiresAt(req.getExpiresAt());
                exist.setGrantedBy(operatorId);
                exist.setGrantedAt(now);
                folderPermMapper.updateById(exist);
            } else {
                folderPermMapper.insert(p);
            }
            log.info("Folder permission granted: folder={}, subject={}:{}, flags={}",
                    req.getResourceId(), req.getSubjectType(), req.getSubjectId(), req.getPermFlags());
        } else if ("file".equalsIgnoreCase(req.getResourceType())) {
            DocFilePermission p = new DocFilePermission()
                    .setFileId(req.getResourceId())
                    .setSubjectType(req.getSubjectType())
                    .setSubjectId(req.getSubjectId())
                    .setPermFlags(req.getPermFlags())
                    .setGrantedBy(operatorId)
                    .setGrantedAt(now)
                    .setExpiresAt(req.getExpiresAt());
            DocFilePermission exist = filePermMapper.selectOne(new QueryWrapper<DocFilePermission>()
                    .eq("file_id", req.getResourceId())
                    .eq("subject_type", req.getSubjectType())
                    .eq("subject_id", req.getSubjectId())
                    .last("LIMIT 1"));
            if (exist != null) {
                exist.setPermFlags(req.getPermFlags());
                exist.setExpiresAt(req.getExpiresAt());
                exist.setGrantedBy(operatorId);
                exist.setGrantedAt(now);
                filePermMapper.updateById(exist);
            } else {
                filePermMapper.insert(p);
            }
            log.info("File permission granted: file={}, subject={}:{}, flags={}",
                    req.getResourceId(), req.getSubjectType(), req.getSubjectId(), req.getPermFlags());
        } else {
            throw new IllegalArgumentException("resourceType 必须是 folder 或 file");
        }
    }

    @Override
    public void revoke(String resourceType, Long resourceId, String subjectType,
                       Long subjectId, Long operatorId) {
        if ("folder".equalsIgnoreCase(resourceType)) {
            folderPermMapper.delete(new QueryWrapper<DocFolderPermission>()
                    .eq("folder_id", resourceId)
                    .eq("subject_type", subjectType)
                    .eq("subject_id", subjectId));
        } else if ("file".equalsIgnoreCase(resourceType)) {
            filePermMapper.delete(new QueryWrapper<DocFilePermission>()
                    .eq("file_id", resourceId)
                    .eq("subject_type", subjectType)
                    .eq("subject_id", subjectId));
        } else {
            throw new IllegalArgumentException("resourceType 必须是 folder 或 file");
        }
        log.info("Permission revoked: type={}, resource={}, subject={}:{}",
                resourceType, resourceId, subjectType, subjectId);
    }

    @Override
    public int computeUserFlags(String resourceType, Long resourceId, Long userId) {
        // 与列表/搜索过滤共用同一主体解析，避免两处口径不一致
        Collection<Long> roleIds = scopeResolver.currentRoleIds();
        Collection<Long> deptIds = scopeResolver.currentDeptIds();
        if ("folder".equalsIgnoreCase(resourceType)) {
            return computeFolderFlags(resourceId, userId, roleIds, deptIds);
        } else if ("file".equalsIgnoreCase(resourceType)) {
            return computeFileFlags(resourceId, userId, roleIds, deptIds);
        }
        throw new IllegalArgumentException("resourceType 必须是 folder 或 file");
    }

    // ================= 统一校验入口 =================

    @Override
    public void requireFile(Long fileId, PermissionFlag flag, Long userId) {
        if (LoginHelper.isSuperAdmin()) return;
        int flags = computeUserFlags("file", fileId, userId);
        if (!PermissionFlag.has(flags, flag)) {
            throw new ServiceException("无" + flag.getDescription() + "权限");
        }
    }

    @Override
    public void requireFolder(Long folderId, PermissionFlag flag, Long userId) {
        // 根层级（0/null）无父资源可校验；新建的资源归创建者所有
        if (folderId == null || folderId == 0L) return;
        if (LoginHelper.isSuperAdmin()) return;
        int flags = computeUserFlags("folder", folderId, userId);
        if (!PermissionFlag.has(flags, flag)) {
            throw new ServiceException("无" + flag.getDescription() + "权限");
        }
    }

    @Override
    public void requireFolders(Collection<Long> folderIds, PermissionFlag flag, Long userId) {
        if (folderIds == null || folderIds.isEmpty()) return;
        if (LoginHelper.isSuperAdmin()) return;
        for (Long id : folderIds) {
            requireFolder(id, flag, userId);
        }
    }

    @Override
    public boolean hasFile(Long fileId, PermissionFlag flag, Long userId) {
        if (LoginHelper.isSuperAdmin()) return true;
        return PermissionFlag.has(computeUserFlags("file", fileId, userId), flag);
    }

    @Override
    public boolean hasFolder(Long folderId, PermissionFlag flag, Long userId) {
        if (folderId == null || folderId == 0L) return true;
        if (LoginHelper.isSuperAdmin()) return true;
        return PermissionFlag.has(computeUserFlags("folder", folderId, userId), flag);
    }

    // ================= 内部方法 =================

    /**
     * 文件夹链求值结果
     *
     * @param owner  链上是否存在「当前用户为该文件夹所有者」（→ 完全控制，不受禁止位影响）
     * @param denied 链上是否命中「禁止访问」
     * @param flags  链上授权位并集（不含禁止位）
     */
    private record FolderEval(boolean owner, boolean denied, int flags) {
    }

    /**
     * 沿「本文件夹 + 父链」求值：所有者 / 禁止 / 授权并集
     */
    private FolderEval evalFolderChain(Long folderId, Long userId,
                                       Collection<Long> roleIds, Collection<Long> deptIds) {
        boolean owner = false;
        boolean denied = false;
        int flags = 0;
        Long cur = folderId;
        Set<Long> visited = new HashSet<>();
        while (cur != null && cur != 0 && visited.add(cur)) {
            var f = folderMapper.selectById(cur);
            if (f == null) {
                break;
            }
            if (userId.equals(f.getOwnerId())) {
                owner = true;
            }
            int rowFlags = folderPermMapper.sumFlags(cur, userId, roleIds, deptIds);
            if ((rowFlags & PermissionFlag.DENY.getCode()) != 0) {
                denied = true;
            }
            flags |= rowFlags;
            cur = f.getParentId();
        }
        return new FolderEval(owner, denied, flags & ~PermissionFlag.DENY.getCode());
    }

    /**
     * 计算文件夹权限
     *
     * <p>优先级：文档区所有者（完全控制，不受禁止位影响）&gt; 禁止访问（返回 0，向下继承）&gt; 授权并集
     */
    private int computeFolderFlags(Long folderId, Long userId,
                                   Collection<Long> roleIds, Collection<Long> deptIds) {
        FolderEval eval = evalFolderChain(folderId, userId, roleIds, deptIds);
        if (eval.owner()) {
            return PermissionFlag.FULL;
        }
        if (eval.denied()) {
            return 0;
        }
        return eval.flags();
    }

    /**
     * 计算文件权限
     *
     * <p>优先级：所在文件夹链的文档区所有者（完全控制）&gt; 禁止（文件级或父链任一命中即返回 0）
     * &gt; 上传者（获得删除权）&gt; 授权并集
     */
    private int computeFileFlags(Long fileId, Long userId,
                                 Collection<Long> roleIds, Collection<Long> deptIds) {
        var file = fileMapper.selectById(fileId);

        // 1) 父链：所有者 / 禁止 / 授权
        FolderEval folderEval = null;
        if (file != null && file.getFolderId() != null) {
            folderEval = evalFolderChain(file.getFolderId(), userId, roleIds, deptIds);
            if (folderEval.owner()) {
                return PermissionFlag.FULL;
            }
        }

        // 2) 文件级授权 / 禁止
        int fileFlags = filePermMapper.sumFlags(fileId, userId, roleIds, deptIds);
        boolean denied = (fileFlags & PermissionFlag.DENY.getCode()) != 0
                || (folderEval != null && folderEval.denied());
        if (denied) {
            // 禁止优先于授予：只要命中过拒绝位，一律不可见（文件级授权也不能覆盖）
            return 0;
        }

        int flags = fileFlags;
        if (folderEval != null) {
            flags |= folderEval.flags();
        }

        // 3) 上传者：可删除自己上传的文件（重命名/移动见 canManageFile）
        if (file != null && userId.equals(file.getCreatorId())) {
            flags |= PermissionFlag.DELETE.getCode();
        }
        return flags & ~PermissionFlag.DENY.getCode();
    }

    @Override
    public boolean canManageFile(Long fileId, Long userId) {
        // 重命名/移动：需「完全控制」，或本人是该文件的上传者
        if (LoginHelper.isSuperAdmin()) {
            return true;
        }
        var file = fileMapper.selectById(fileId);
        if (file == null) {
            return false;
        }
        if (userId != null && userId.equals(file.getCreatorId())) {
            return true;
        }
        Collection<Long> roleIds = scopeResolver.currentRoleIds();
        Collection<Long> deptIds = scopeResolver.currentDeptIds();
        return PermissionFlag.has(computeFileFlags(fileId, userId, roleIds, deptIds),
                PermissionFlag.FULL_CONTROL);
    }

    @Override
    public void requireFileManageable(Long fileId, Long userId) {
        if (!canManageFile(fileId, userId)) {
            throw new ServiceException("无重命名/移动权限（需完全控制，或为本人上传的文件）");
        }
    }
}
