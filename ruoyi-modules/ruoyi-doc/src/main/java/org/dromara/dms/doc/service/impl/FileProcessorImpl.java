package org.dromara.dms.doc.service.impl;

import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.config.MinIoConfig;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFileTextMapper;
import org.dromara.dms.doc.service.FileProcessor;
import org.dromara.dms.doc.service.FileTypeDetector;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 文件处理实现（异步）
 *
 * <p>流程：
 * <ol>
 *   <li>从 MinIO 下载原文件到临时目录</li>
 *   <li>用 Apache Tika 识别真实 MIME 类型</li>
 *   <li>提取文本（PDF/Office/文本），写入 doc_file_text（全文搜索用）</li>
 *   <li>更新 doc_file 的 mime_type</li>
 *   <li>删除临时文件</li>
 * </ol>
 *
 * <p>v1.1 扩展点：Office→PDF（jodconverter）、缩略图生成、视频海报帧等。
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessorImpl implements FileProcessor {

    private final FileTypeDetector detector;
    private final DocFileMapper fileMapper;
    private final DocFileTextMapper fileTextMapper;
    private final MinioClient minioClient;
    private final MinIoConfig minIoConfig;

    @Override
    @Async
    public void processAsync(DocFile file) {
        processSync(file);
    }

    @Override
    public void processSync(DocFile file) {
        log.info("Processing file id={}, name={}, bucket={}, key={}",
                file.getFileId(), file.getFileName(), file.getStorageBucket(), file.getStorageKey());

        Path tmp = null;
        try {
            // 1. 建临时目录 + 目标文件（MinIO downloadObject 要求目标文件不存在）
            Path tmpDir = Files.createTempDirectory("dms-proc-");
            tmp = tmpDir.resolve(file.getFileName() == null ? "download" : file.getFileName());
            minioClient.downloadObject(DownloadObjectArgs.builder()
                    .bucket(file.getStorageBucket())
                    .object(file.getStorageKey())
                    .filename(tmp.toString())
                    .build());
            File local = tmp.toFile();

            // 2. Tika 识别真实 MIME
            String mime = detector.detect(local);
            log.info("Detected mime={} for file {}", mime, file.getFileName());

            // 3. 提取文本（PDF/Office/文本/图片 OCR）
            String text = detector.extractText(local);
            if (!text.isBlank()) {
                fileTextMapper.upsert(file.getFileId(), text, LocalDateTime.now());
                log.info("Extracted {} chars text for file id={}", text.length(), file.getFileId());
            } else {
                log.info("No text extracted for file id={}", file.getFileId());
            }

            // 4. 更新 doc_file 元数据
            DocFile update = new DocFile();
            update.setFileId(file.getFileId());
            update.setMimeType(mime);
            fileMapper.updateById(update);

        } catch (Exception e) {
            log.error("File processing failed for id={}, name={}",
                    file.getFileId(), file.getFileName(), e);
        } finally {
            // 5. 清理临时文件 + 目录
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                    Files.deleteIfExists(tmp.getParent());
                } catch (Exception ignored) {
                }
            }
        }
    }
}
