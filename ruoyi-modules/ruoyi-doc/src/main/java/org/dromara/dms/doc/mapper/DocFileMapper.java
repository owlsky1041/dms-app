package org.dromara.dms.doc.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.dms.doc.domain.DocFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件 Mapper
 *
 * @author DMS
 */
@Mapper
public interface DocFileMapper extends MPJBaseMapper<DocFile> {

    /**
     * 获取文件所在文件夹 ID
     */
    @Select("SELECT folder_id FROM doc_file WHERE file_id = #{fileId} AND deleted_at IS NULL")
    Long getFolderId(@Param("fileId") Long fileId);

    /**
     * 列出文件夹下文件（不含已删除）
     */
    @Select("SELECT * FROM doc_file WHERE folder_id = #{folderId} AND deleted_at IS NULL ORDER BY create_time DESC")
    List<DocFile> listByFolder(@Param("folderId") Long folderId);

    /**
     * 分页列出文件夹下文件
     */
    @Select("SELECT * FROM doc_file WHERE folder_id = #{folderId} AND deleted_at IS NULL ORDER BY create_time DESC LIMIT #{page.size} OFFSET #{page.current}")
    IPage<DocFile> pageByFolder(Page<DocFile> page, @Param("folderId") Long folderId);

    /**
     * 软删除
     */
    @Update("UPDATE doc_file SET deleted_at = #{now} WHERE file_id = #{fileId} AND deleted_at IS NULL")
    int softDelete(@Param("fileId") Long fileId, @Param("now") LocalDateTime now);

    /**
     * 恢复
     */
    @Update("UPDATE doc_file SET deleted_at = NULL WHERE file_id = #{fileId}")
    int restore(@Param("fileId") Long fileId);

    /**
     * 物理删除
     */
    @Update("DELETE FROM doc_file WHERE file_id = #{fileId}")
    int hardDelete(@Param("fileId") Long fileId);

    /**
     * 重命名
     */
    @Update("UPDATE doc_file SET file_name = #{name}, update_time = #{now} WHERE file_id = #{fileId} AND deleted_at IS NULL")
    int rename(@Param("fileId") Long fileId, @Param("name") String name, @Param("now") LocalDateTime now);

    /**
     * 移动
     */
    @Update("UPDATE doc_file SET folder_id = #{folderId}, update_time = #{now} WHERE file_id = #{fileId} AND deleted_at IS NULL")
    int move(@Param("fileId") Long fileId, @Param("folderId") Long folderId, @Param("now") LocalDateTime now);

    /**
     * 检查同文件夹下同名文件
     */
    @Select("SELECT COUNT(*) > 0 FROM doc_file WHERE folder_id = #{folderId} AND file_name = #{name} AND deleted_at IS NULL")
    boolean existsByName(@Param("folderId") Long folderId, @Param("name") String name);

    /**
     * 根据 SHA-256 查重（秒传）
     */
    @Select("SELECT * FROM doc_file WHERE file_hash = #{hash} AND deleted_at IS NULL LIMIT 1")
    DocFile findByHash(@Param("hash") String hash);

    /**
     * 全文搜索（文件名 ILIKE + 提取文本 ILIKE，支持中英文；pg_trgm 索引加速前缀/模糊）
     */
    @Select(value = "SELECT f.* FROM doc_file f " +
            "LEFT JOIN doc_file_text t ON t.file_id = f.file_id " +
            "WHERE f.deleted_at IS NULL AND ( " +
            "  f.file_name ILIKE '%' || #{keyword} || '%' " +
            "  OR f.description ILIKE '%' || #{keyword} || '%' " +
            "  OR t.extract_text ILIKE '%' || #{keyword} || '%' " +
            ") ORDER BY f.create_time DESC LIMIT #{limit}")
    List<DocFile> fulltextSearch(@Param("keyword") String keyword, @Param("limit") int limit);
}
