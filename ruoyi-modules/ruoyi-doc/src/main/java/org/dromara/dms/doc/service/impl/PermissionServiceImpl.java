package org.dromara.dms.doc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.domain.DocFilePermission;
import org.dromara.dms.doc.domain.DocFolder;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        // 规格化：禁止位独占；任何授予隐含「可见」；「下载」隐含「预览」。
        // 放在这里而不是靠前端，是为了让「下载必然含只读」成为数据层不变式
        int permFlags = PermissionFlag.normalize(req.getPermFlags());
        if (permFlags == 0) {
            throw new ServiceException("请至少选择一项权限");
        }
        LocalDateTime now = LocalDateTime.now();

        if ("folder".equalsIgnoreCase(req.getResourceType())) {
            DocFolderPermission p = new DocFolderPermission()
                    .setFolderId(req.getResourceId())
                    .setSubjectType(req.getSubjectType())
                    .setSubjectId(req.getSubjectId())
                    .setPermFlags(permFlags)
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
                exist.setPermFlags(permFlags);
                exist.setInheritToChildren(p.getInheritToChildren());
                exist.setExpiresAt(req.getExpiresAt());
                exist.setGrantedBy(operatorId);
                exist.setGrantedAt(now);
                folderPermMapper.updateById(exist);
            } else {
                folderPermMapper.insert(p);
            }
            log.info("Folder permission granted: folder={}, subject={}:{}, flags={}",
                    req.getResourceId(), req.getSubjectType(), req.getSubjectId(), permFlags);
        } else if ("file".equalsIgnoreCase(req.getResourceType())) {
            DocFilePermission p = new DocFilePermission()
                    .setFileId(req.getResourceId())
                    .setSubjectType(req.getSubjectType())
                    .setSubjectId(req.getSubjectId())
                    .setPermFlags(permFlags)
                    .setGrantedBy(operatorId)
                    .setGrantedAt(now)
                    .setExpiresAt(req.getExpiresAt());
            DocFilePermission exist = filePermMapper.selectOne(new QueryWrapper<DocFilePermission>()
                    .eq("file_id", req.getResourceId())
                    .eq("subject_type", req.getSubjectType())
                    .eq("subject_id", req.getSubjectId())
                    .last("LIMIT 1"));
            if (exist != null) {
                exist.setPermFlags(permFlags);
                exist.setExpiresAt(req.getExpiresAt());
                exist.setGrantedBy(operatorId);
                exist.setGrantedAt(now);
                filePermMapper.updateById(exist);
            } else {
                filePermMapper.insert(p);
            }
            log.info("File permission granted: file={}, subject={}:{}, flags={}",
                    req.getResourceId(), req.getSubjectType(), req.getSubjectId(), permFlags);
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
    public List<Map<String, Object>> listEffectivePermissions(String resourceType, Long resourceId,
                                                              Long operatorId) {
        List<Map<String, Object>> result = new ArrayList<>();

        if ("folder".equalsIgnoreCase(resourceType)) {
            DocFolder folder = folderMapper.selectById(resourceId);
            if (folder == null) {
                return result;
            }
            // 1) 本层授权（可撤销）
            for (DocFolderPermission p : folderPermMapper.listByFolder(resourceId)) {
                result.add(toVo(p.getPermId(), "direct", folder, p.getSubjectType(), p.getSubjectId(),
                        p.getPermFlags(), p.getInheritToChildren(), p.getExpiresAt(), p.getGrantedBy()));
            }
            // 2) 祖先链上的授权（只读展示）
            for (Long ancestorId : ancestorFolderIds(folder)) {
                DocFolder ancestor = folderMapper.selectById(ancestorId);
                if (ancestor == null) {
                    continue;
                }
                for (DocFolderPermission p : folderPermMapper.listByFolder(ancestorId)) {
                    result.add(toVo(p.getPermId(), "inherited", ancestor, p.getSubjectType(), p.getSubjectId(),
                            p.getPermFlags(), p.getInheritToChildren(), p.getExpiresAt(), p.getGrantedBy()));
                }
            }
            return result;
        }

        if ("file".equalsIgnoreCase(resourceType)) {
            DocFile file = fileMapper.selectById(resourceId);
            if (file == null) {
                return result;
            }
            // 1) 文件级授权
            for (DocFilePermission p : filePermMapper.listByFile(resourceId)) {
                result.add(toFileVo(p.getPermId(), "direct", null, p.getSubjectType(), p.getSubjectId(),
                        p.getPermFlags(), p.getExpiresAt()));
            }
            // 2) 所在文件夹及其祖先链上的授权
            if (file.getFolderId() != null) {
                DocFolder parent = folderMapper.selectById(file.getFolderId());
                if (parent != null) {
                    List<Long> chain = new ArrayList<>();
                    chain.add(parent.getFolderId());
                    chain.addAll(ancestorFolderIds(parent));
                    for (Long fid : chain) {
                        DocFolder f = folderMapper.selectById(fid);
                        if (f == null) {
                            continue;
                        }
                        for (DocFolderPermission p : folderPermMapper.listByFolder(fid)) {
                            result.add(toFileVo(p.getPermId(), "inherited", f, p.getSubjectType(),
                                    p.getSubjectId(), p.getPermFlags(), p.getExpiresAt()));
                        }
                    }
                }
            }
            return result;
        }
        throw new IllegalArgumentException("resourceType 必须是 folder 或 file");
    }

    /** 由物化路径解析祖先文件夹 ID（不含自身、不含虚拟根 0） */
    private List<Long> ancestorFolderIds(DocFolder folder) {
        Set<Long> ids = new LinkedHashSet<>();
        String path = folder.getFolderPath();
        if (path == null || path.isBlank()) {
            return List.of();
        }
        for (String part : path.split("/")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(s);
                // 0 是虚拟根；自身要排除（本层授权已单独列出）
                if (id != 0L && !Long.valueOf(id).equals(folder.getFolderId())) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 忽略非法片段
            }
        }
        return new ArrayList<>(ids);
    }

    /** 组装一条「生效授权」的返回结构 */
    private Map<String, Object> toVo(Long permId, String sourceType, DocFolder source,
                                     String subjectType, Long subjectId, int permFlags,
                                     Boolean inheritToChildren, LocalDateTime expiresAt, Long grantedBy) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("permId", permId);
        vo.put("sourceType", sourceType);
        if (source != null) {
            vo.put("sourceFolderId", source.getFolderId());
            vo.put("sourceFolderName", source.getFolderName());
            vo.put("sourceFolderPath", source.getFolderPath());
        }
        vo.put("subjectType", subjectType);
        vo.put("subjectId", subjectId);
        vo.put("permFlags", permFlags);
        vo.put("inheritToChildren", inheritToChildren);
        vo.put("expiresAt", expiresAt);
        vo.put("grantedBy", grantedBy);
        return vo;
    }

    /** 文件继承来源没有 inherit_to_children 语义，单独组装 */
    private Map<String, Object> toFileVo(Long permId, String sourceType, DocFolder source,
                                         String subjectType, Long subjectId, int permFlags,
                                         LocalDateTime expiresAt) {
        return toVo(permId, sourceType, source, subjectType, subjectId, permFlags, null, expiresAt, null);
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

    @Override
    public Map<Long, Integer> computeFileFlagsBatch(List<DocFile> files, Long userId) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (files == null || files.isEmpty()) {
            return result;
        }
        if (LoginHelper.isSuperAdmin()) {
            files.forEach(f -> result.put(f.getFileId(), PermissionFlag.FULL));
            return result;
        }
        Collection<Long> roleIds = scopeResolver.currentRoleIds();
        Collection<Long> deptIds = scopeResolver.currentDeptIds();
        // 同一目录的文件共享目录链结果，缓存一次即可
        Map<Long, FolderEval> folderCache = new HashMap<>();
        for (DocFile f : files) {
            FolderEval fe = null;
            if (f.getFolderId() != null) {
                fe = folderCache.computeIfAbsent(f.getFolderId(),
                        fid -> evalFolderChain(fid, userId, roleIds, deptIds));
                if (fe.owner()) {
                    result.put(f.getFileId(), PermissionFlag.FULL);
                    continue;
                }
            }
            int fileFlags = filePermMapper.sumFlags(f.getFileId(), userId, roleIds, deptIds);
            if ((fileFlags & PermissionFlag.DENY.getCode()) != 0 || (fe != null && fe.denied())) {
                result.put(f.getFileId(), 0);
                continue;
            }
            int flags = fileFlags | (fe != null ? fe.flags() : 0);
            if (userId != null && userId.equals(f.getCreatorId())) {
                flags |= PermissionFlag.DELETE.getCode();
            }
            result.put(f.getFileId(), flags & ~PermissionFlag.DENY.getCode());
        }
        return result;
    }

    @Override
    public Map<Long, Integer> computeFolderFlagsBatch(List<DocFolder> folders, Long userId) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (folders == null || folders.isEmpty()) {
            return result;
        }
        if (LoginHelper.isSuperAdmin()) {
            folders.forEach(f -> result.put(f.getFolderId(), PermissionFlag.FULL));
            return result;
        }
        Collection<Long> roleIds = scopeResolver.currentRoleIds();
        Collection<Long> deptIds = scopeResolver.currentDeptIds();
        Map<Long, FolderEval> cache = new HashMap<>();
        for (DocFolder f : folders) {
            FolderEval eval = cache.computeIfAbsent(f.getFolderId(),
                    fid -> evalFolderChain(fid, userId, roleIds, deptIds));
            if (eval.owner()) {
                result.put(f.getFolderId(), PermissionFlag.FULL);
            } else if (eval.denied()) {
                result.put(f.getFolderId(), 0);
            } else {
                result.put(f.getFolderId(), eval.flags());
            }
        }
        return result;
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
        // 改名/移动看「编辑」位；「完全控制」也接受（历史数据只有 128 的情况）
        return PermissionFlag.canManage(computeFileFlags(fileId, userId, roleIds, deptIds));
    }

    @Override
    public void requireFileManageable(Long fileId, Long userId) {
        if (!canManageFile(fileId, userId)) {
            throw new ServiceException("无重命名/移动权限（需读写及以上档位，或为本人上传的文件）");
        }
    }

    @Override
    public void requireFolderManageable(Long folderId, Long userId) {
        if (folderId == null || folderId == 0L) {
            return;
        }
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        int flags = computeUserFlags("folder", folderId, userId);
        if (!PermissionFlag.canManage(flags)) {
            throw new ServiceException("无重命名/移动权限（需读写及以上档位）");
        }
    }
}
