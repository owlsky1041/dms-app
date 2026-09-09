package org.dromara.dms.doc.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.dms.doc.domain.DocFolderPermission;

import java.util.Collection;
import java.util.List;

/**
 * 文件夹权限 Mapper
 *
 * @author DMS
 */
@Mapper
public interface DocFolderPermissionMapper extends MPJBaseMapper<DocFolderPermission> {

    /**
     * 汇总一个文件夹在某用户上的有效权限位（取所有 subject 的 OR）
     *
     * <p>subject 类型：user / role / dept
     * user 取 user_id 匹配；role 取 sys_user_role 关联；dept 取 dept_id 匹配
     */
    @Select(value = "SELECT COALESCE(BIT_OR(perm_flags), 0) FROM doc_folder_permission " +
            "WHERE folder_id = #{folderId} " +
            "  AND (deleted_at IS NULL OR deleted_at > NOW()) " +  // 注：权限表没有 deleted_at 字段
            "  AND ( " +
            "    (subject_type = 'user' AND subject_id = #{userId}) " +
            "    OR (subject_type = 'role' AND subject_id IN (SELECT role_id FROM sys_user_role WHERE user_id = #{userId})) " +
            "    OR (subject_type = 'dept' AND subject_id IN (SELECT ancestor_id FROM sys_Dept_ancestor WHERE descendant_id = #{deptId})) " +
            "  )")
    int sumFlags(@Param("folderId") Long folderId,
                 @Param("userId") Long userId,
                 @Param("roleIds") Collection<Long> roleIds,
                 @Param("deptIds") Collection<Long> deptIds);

    /**
     * 列出文件夹的所有权限条目
     */
    @Select("SELECT * FROM doc_folder_permission WHERE folder_id = #{folderId} ORDER BY granted_at DESC")
    List<DocFolderPermission> listByFolder(@Param("folderId") Long folderId);

    /**
     * 删除文件夹的所有权限（删除文件夹时级联清理）
     */
    @Delete("DELETE FROM doc_folder_permission WHERE folder_id = #{folderId}")
    int deleteByFolder(@Param("folderId") Long folderId);
}
