package org.dromara.dms.doc.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.dms.doc.domain.DocFile;

import java.io.IOException;
import java.util.List;

/**
 * 文件服务接口
 *
 * @author DMS
 */
public interface FileService {

    /**
     * 分页列出文件夹下文件
     */
    IPage<DocFile> pageByFolder(Long folderId, int page, int size, Long userId);

    /**
     * 文件详情（带权限标记）
     */
    DocFile getById(Long fileId, Long userId);

    /**
     * 重命名
     */
    void rename(Long fileId, String newName, Long userId);

    /**
     * 移动
     */
    void move(Long fileId, Long targetFolderId, Long userId);

    /**
     * 软删除
     */
    void softDelete(Long fileId, Long userId);

    /**
     * 恢复
     */
    void restore(Long fileId, Long userId);

    /**
     * 永久删除
     */
    void hardDelete(Long fileId, Long userId);

    /**
     * 全局搜索（PostgreSQL tsvector + pg_trgm）
     */
    List<DocFile> search(String keyword, int limit, Long userId);

    /**
     * 下载原文件（支持 HTTP Range）
     */
    void download(Long fileId, HttpServletRequest request, HttpServletResponse response) throws IOException;

    /**
     * 流式输出文件内容（预览用，浏览器 Content-Type 渲染）
     */
    void streamContent(Long fileId, HttpServletRequest request, HttpServletResponse response) throws IOException;

    /**
     * 流式输出缩略图
     */
    void streamThumbnail(Long fileId, HttpServletResponse response) throws IOException;
}
