package org.dromara.dms.doc.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.dms.doc.domain.DocFilePermission;

import java.util.Collection;
import java.util.List;

/**
 * 文件权限 Mapper
 *
 * @author DMS
 */
@Mapper
public interface DocFilePermissionMapper extends MPJBaseMapper<DocFilePermission> {

    /**
     * 汇总文件权限位（主体集合由服务层展开）
     */
    @Select("<script>" +
            "SELECT COALESCE(BIT_OR(perm_flags), 0) FROM doc_file_permission " +
            "WHERE file_id = #{fileId} " +
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
    int sumFlags(@Param("fileId") Long fileId,
                 @Param("userId") Long userId,
                 @Param("roleIds") Collection<Long> roleIds,
                 @Param("deptIds") Collection<Long> deptIds);

    @Select("SELECT * FROM doc_file_permission WHERE file_id = #{fileId} ORDER BY granted_at DESC")
    List<DocFilePermission> listByFile(@Param("fileId") Long fileId);

    @Delete("DELETE FROM doc_file_permission WHERE file_id = #{fileId}")
    int deleteByFile(@Param("fileId") Long fileId);
}
