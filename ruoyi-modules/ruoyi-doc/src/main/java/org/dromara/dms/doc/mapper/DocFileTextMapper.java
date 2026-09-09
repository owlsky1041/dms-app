package org.dromara.dms.doc.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.dms.doc.domain.DocFileText;

import java.time.LocalDateTime;

/**
 * 文件文本 Mapper（doc_file_text，全文搜索用）
 *
 * @author DMS
 */
@Mapper
public interface DocFileTextMapper extends MPJBaseMapper<DocFileText> {

    /**
     * 插入或更新提取文本（PostgreSQL ON CONFLICT）
     */
    @Insert("INSERT INTO doc_file_text(file_id, extract_text, extract_time) " +
            "VALUES(#{fileId}, #{text}, #{time}) " +
            "ON CONFLICT (file_id) DO UPDATE SET extract_text = EXCLUDED.extract_text, extract_time = EXCLUDED.extract_time")
    int upsert(@Param("fileId") Long fileId,
               @Param("text") String text,
               @Param("time") LocalDateTime time);

    /**
     * 查询提取文本
     */
    @Select("SELECT extract_text FROM doc_file_text WHERE file_id = #{fileId}")
    String selectText(@Param("fileId") Long fileId);
}
