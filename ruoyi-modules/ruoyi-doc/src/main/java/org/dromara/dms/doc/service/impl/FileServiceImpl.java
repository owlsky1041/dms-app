package org.dromara.dms.doc.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.service.FileService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件服务实现（v1.0 简化版）
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final DocFileMapper fileMapper;

    @Override
    public IPage<DocFile> pageByFolder(Long folderId, int page, int size, Long userId) {
        return fileMapper.pageByFolder(new Page<>(page, size), folderId);
    }

    @Override
    public DocFile getById(Long fileId, Long userId) {
        DocFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        return file;
    }

    @Override
    public void rename(Long fileId, String newName, Long userId) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        fileMapper.rename(fileId, newName, LocalDateTime.now());
    }

    @Override
    public void move(Long fileId, Long targetFolderId, Long userId) {
        fileMapper.move(fileId, targetFolderId, LocalDateTime.now());
    }

    @Override
    public void softDelete(Long fileId, Long userId) {
        fileMapper.softDelete(fileId, LocalDateTime.now());
    }

    @Override
    public void restore(Long fileId, Long userId) {
        fileMapper.restore(fileId);
    }

    @Override
    public void hardDelete(Long fileId, Long userId) {
        fileMapper.hardDelete(fileId);
    }

    @Override
    public List<DocFile> search(String keyword, int limit, Long userId) {
        return fileMapper.fulltextSearch(keyword, limit);
    }
}
