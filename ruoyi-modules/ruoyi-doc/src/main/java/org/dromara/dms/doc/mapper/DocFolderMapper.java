package org.dromara.dms.doc.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.dms.doc.domain.DocFolder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件夹 Mapper
 *
 * <p>继承 {@link MPJBaseMapper} 获得 MyBatis-Plus-Join 联表能力
 *
 * @author DMS
 */
@Mapper
public interface DocFolderMapper extends MPJBaseMapper<DocFolder> {

    /**
     * 根据 ID 查询（包含已删除的，回收站用）
     */
    @Select("SELECT * FROM doc_folder WHERE folder_id = #{folderId}")
    DocFolder selectByIdIncludeDeleted(@Param("folderId") Long folderId);

    /**
     * 获取父文件夹 ID
     */
    @Select("SELECT parent_id FROM doc_folder WHERE folder_id = #{folderId} AND deleted_at IS NULL")
    Long getParentId(@Param("folderId") Long folderId);

    /**
     * 查询子文件夹（不含已删除）
     */
    @Select("SELECT * FROM doc_folder WHERE parent_id = #{parentId} AND deleted_at IS NULL ORDER BY sort_order, folder_name")
    List<DocFolder> listChildren(@Param("parentId") Long parentId);

    /**
     * 按路径前缀查询所有子文件夹（用于移动/删除整个子树）
     */
    @Select("SELECT * FROM doc_folder WHERE folder_path LIKE CONCAT(#{pathPrefix}, '%') AND deleted_at IS NULL")
    List<DocFolder> listDescendants(@Param("pathPrefix") String pathPrefix);

    /**
     * 软删除
     */
    @Update("UPDATE doc_folder SET deleted_at = #{now} WHERE folder_id = #{folderId} AND deleted_at IS NULL")
    int softDelete(@Param("folderId") Long folderId, @Param("now") LocalDateTime now);

    /**
     * 恢复（清除 deleted_at）
     */
    @Update("UPDATE doc_folder SET deleted_at = NULL WHERE folder_id = #{folderId}")
    int restore(@Param("folderId") Long folderId);

    /**
     * 列出回收站文件夹（软删除的）
     */
    @Select("SELECT * FROM doc_folder WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    List<DocFolder> listDeletedFolders();

    /**
     * 回收站文件夹（仅本人所有）—— 普通用户只能看到自己的回收站
     */
    @Select("SELECT * FROM doc_folder WHERE deleted_at IS NOT NULL AND owner_id = #{userId} ORDER BY deleted_at DESC")
    List<DocFolder> listDeletedFoldersByOwner(@Param("userId") Long userId);

    /**
     * 回收站中已超过保留期的文件夹
     *
     * <p>按 folder_path 升序：父目录的路径是其子目录路径的前缀，排序后父一定在子之前，
     * 这样清理时先删父（连同子树），子目录的分支会被跳过而不会重复处理。
     */
    @Select("SELECT * FROM doc_folder WHERE deleted_at IS NOT NULL AND deleted_at < #{cutoff} "
          + "ORDER BY folder_path, folder_id")
    List<DocFolder> listExpiredDeletedFolders(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 物理删除文件夹（连同其物化路径子树）
     */
    @Select("SELECT folder_id FROM doc_folder WHERE folder_path LIKE CONCAT(#{pathPrefix}, '%') OR folder_id = #{folderId}")
    List<Long> listSubtreeIds(@Param("folderId") Long folderId, @Param("pathPrefix") String pathPrefix);

    /**
     * 批量平移子树的物化路径（移动文件夹后同步后代）
     *
     * <p>用 overlay 做前缀替换而不是 replace：replace 会把路径中间偶然出现的
     * 相同片段也换掉，overlay 只替换开头的固定长度前缀，语义准确。
     */
    @Update("UPDATE doc_folder SET folder_path = overlay(folder_path placing #{newPrefix} from 1 for length(#{oldPrefix})), "
          + "update_time = #{now} WHERE folder_path LIKE CONCAT(#{oldPrefix}, '%')")
    int updateDescendantPaths(@Param("oldPrefix") String oldPrefix,
                              @Param("newPrefix") String newPrefix,
                              @Param("now") LocalDateTime now);

    /**
     * 物理删除
     */
    @Update("DELETE FROM doc_folder WHERE folder_id = #{folderId}")
    int hardDelete(@Param("folderId") Long folderId);

    /**
     * 重命名
     */
    @Update("UPDATE doc_folder SET folder_name = #{name}, update_time = #{now} WHERE folder_id = #{folderId} AND deleted_at IS NULL")
    int rename(@Param("folderId") Long folderId, @Param("name") String name, @Param("now") LocalDateTime now);

    /**
     * 移动（更新 parent_id 和 folder_path）
     */
    @Update("UPDATE doc_folder SET parent_id = #{newParentId}, folder_path = #{newPath}, update_time = #{now} WHERE folder_id = #{folderId} AND deleted_at IS NULL")
    int move(@Param("folderId") Long folderId, @Param("newParentId") Long newParentId,
             @Param("newPath") String newPath, @Param("now") LocalDateTime now);

    /**
     * 按名字查子文件夹（用于「合并内容」时定位已存在的同名文件夹）
     */
    @Select("SELECT * FROM doc_folder WHERE parent_id = #{parentId} AND folder_name = #{name} "
          + "AND deleted_at IS NULL ORDER BY folder_id LIMIT 1")
    DocFolder findChildByName(@Param("parentId") Long parentId, @Param("name") String name);

    /**
     * 检查同名文件夹是否存在（同 parent_id 下）
     */
    @Select("SELECT COUNT(*) > 0 FROM doc_folder WHERE parent_id = #{parentId} AND folder_name = #{name} AND deleted_at IS NULL")
    boolean existsByName(@Param("parentId") Long parentId, @Param("name") String name);

    /**
     * 创建根文件夹（每个用户一个）
     */
    @Insert("INSERT INTO doc_folder(parent_id, folder_path, folder_name, owner_id, dept_id, create_by, create_time) "
          + "VALUES(0, '/0/', #{folderName}, #{ownerId}, #{deptId}, #{createBy}, CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "folderId", keyColumn = "folder_id")
    void createRootFolder(DocFolder folder);
}
