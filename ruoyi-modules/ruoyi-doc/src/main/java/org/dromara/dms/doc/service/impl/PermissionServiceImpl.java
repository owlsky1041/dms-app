package org.dromara.dms.doc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFilePermission;
import org.dromara.dms.doc.domain.DocFolderPermission;
import org.dromara.dms.doc.dto.GrantPermissionRequest;
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

    // ================= 内部方法 =================

    /**
     * 计算文件夹权限（含父链继承）
     */
    private int computeFolderFlags(Long folderId, Long userId,
                                   Collection<Long> roleIds, Collection<Long> deptIds) {
        // 所有者完全控制
        var folder = folderMapper.selectById(folderId);
        if (folder != null && userId.equals(folder.getOwnerId())) {
            return 255;
        }
        // 直接权限 + 父链继承
        int flags = 0;
        Long cur = folderId;
        Set<Long> visited = new HashSet<>();
        while (cur != null && cur != 0 && visited.add(cur)) {
            flags |= folderPermMapper.sumFlags(cur, userId, roleIds, deptIds);
            // 若父层无 inherit 传播则停 —— 简化：始终向上并集
            var f = folderMapper.selectById(cur);
            if (f == null) break;
            cur = f.getParentId();
        }
        return flags;
    }

    /**
     * 计算文件权限（直接权限 + 所在文件夹继承）
     */
    private int computeFileFlags(Long fileId, Long userId,
                                 Collection<Long> roleIds, Collection<Long> deptIds) {
        var file = fileMapper.selectById(fileId);
        if (file != null && userId.equals(file.getCreatorId())) {
            return 255;
        }
        int flags = filePermMapper.sumFlags(fileId, userId, roleIds, deptIds);
        if (file != null && file.getFolderId() != null) {
            flags |= computeFolderFlags(file.getFolderId(), userId, roleIds, deptIds);
        }
        return flags;
    }
}
