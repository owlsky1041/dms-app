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
     * 汇总文件夹在某用户主体集合上的有效权限位
     *
     * <p>调用方需预先展开主体集合：userId + 用户全部 roleId + 用户部门及其所有祖先 deptId。
     */
    @Select("<script>" +
            "SELECT COALESCE(BIT_OR(perm_flags), 0) FROM doc_folder_permission " +
            "WHERE folder_id = #{folderId} " +
            "  AND (expires_at IS NULL OR expires_at &gt; NOW()) " +
            "  AND ( " +
            "    (subject_type = 'user' AND subject_id = #{userId}) " +
            "    <if test='roleIds != null and roleIds.size() &gt; 0'>" +
            "    OR (subject_type = 'role' AND subject_id IN " +
            "        <foreach collection='roleIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach>)" +
            "    </if>" +
            "    <if test='deptIds != null and deptIds.size() &gt; 0'>" +
            "    OR (subject_type = 'dept' AND subject_id IN " +
            "        <foreach collection='deptIds' item='did' open='(' separator=',' close=')'>#{did}</foreach>)" +
            "    </if>" +
            "  )" +
            "</script>")
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
