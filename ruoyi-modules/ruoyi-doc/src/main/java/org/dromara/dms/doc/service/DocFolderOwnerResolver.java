package org.dromara.dms.doc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.domain.DocFolder;
import org.dromara.dms.doc.mapper.DocFolderMapper;
import org.springframework.stereotype.Component;

/**
 * 文档区所有者解析器
 *
 * <p>规则：<b>完全控制权归文档区（顶层文件夹）所有者</b>。
 * 因此在某个文档区内新建的文件夹，其 owner_id 继承该文档区的所有者，
 * 而不是记录为创建者 —— 否则任何人在他人文档区里建个子文件夹就能拿到完全控制权。
 *
 * <p>创建者仍记录在 create_by 字段，用于审计。文件的上传者记录在 doc_file.creator_id。
 *
 * @author DMS
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocFolderOwnerResolver {

    private final DocFolderMapper folderMapper;

    /**
     * 解析新建文件夹应归属的所有者
     *
     * @param parentFolderId 父文件夹（0 表示顶层文档区本身）
     * @param fallbackUserId 取不到文档区所有者时的兜底（创建者）
     * @return owner_id
     */
    public Long resolveOwner(Long parentFolderId, Long fallbackUserId) {
        if (parentFolderId == null || parentFolderId == 0L) {
            // 顶层文档区：所有者即创建者（接口层已限制仅超级管理员可建）
            return fallbackUserId;
        }
        Long cur = parentFolderId;
        Long areaOwner = null;
        int safety = 64;  // 防环
        while (cur != null && cur != 0L && safety-- > 0) {
            DocFolder f = folderMapper.selectById(cur);
            if (f == null) {
                break;
            }
            areaOwner = f.getOwnerId();
            cur = f.getParentId();
        }
        return areaOwner != null ? areaOwner : fallbackUserId;
    }
}
