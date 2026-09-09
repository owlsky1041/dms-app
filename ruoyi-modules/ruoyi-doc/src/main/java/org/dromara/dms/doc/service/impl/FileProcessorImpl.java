package org.dromara.dms.doc.service.impl;

import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.config.MinIoConfig;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.mapper.DocFileTextMapper;
import org.dromara.dms.doc.service.FileProcessor;
import org.dromara.dms.doc.service.FileTypeDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 文件处理实现（异步）
 *
 * <p>流程：
 * <ol>
 *   <li>从 MinIO 下载原文件到临时目录</li>
 *   <li>用 Apache Tika 识别真实 MIME 类型</li>
 *   <li>提取文本（PDF/Office/文本），写入 doc_file_text（全文搜索用）</li>
 *   <li>Office 文档 → LibreOffice(soffice CLI) 转 PDF → 存 MinIO preview_key</li>
 *   <li>更新 doc_file 的 mime_type / preview_key</li>
 *   <li>清理临时文件</li>
 * </ol>
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessorImpl implements FileProcessor {

    /** 需要转 PDF 预览的 Office 扩展名 */
    private static final Set<String> OFFICE_EXTS = Set.of(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf");

    private final FileTypeDetector detector;
    private final DocFileMapper fileMapper;
    private final DocFileTextMapper fileTextMapper;
    private final MinioClient minioClient;
    private final MinIoConfig minIoConfig;

    @Value("${dms.office.home:/usr/lib/libreoffice}")
    private String officeHome;

    @Override
    @Async
    public void processAsync(DocFile file) {
        processSync(file);
    }

    @Override
    public void processSync(DocFile file) {
        log.info("Processing file id={}, name={}, bucket={}, key={}",
                file.getFileId(), file.getFileName(), file.getStorageBucket(), file.getStorageKey());

        Path tmpDir = null;
        try {
            // 1. 建临时目录 + 下载原文件
            tmpDir = Files.createTempDirectory("dms-proc-");
            Path localPath = tmpDir.resolve(sanitize(file.getFileName()));
            minioClient.downloadObject(DownloadObjectArgs.builder()
                    .bucket(file.getStorageBucket())
                    .object(file.getStorageKey())
                    .filename(localPath.toString())
                    .build());
            File local = localPath.toFile();

            // 2. Tika 识别真实 MIME
            String mime = detector.detect(local);
            log.info("Detected mime={} for file {}", mime, file.getFileName());

            // 3. 提取文本（PDF/Office/文本/图片 OCR）
            try {
                String text = detector.extractText(local);
                if (text != null && !text.isBlank()) {
                    fileTextMapper.upsert(file.getFileId(), text, LocalDateTime.now());
                    log.info("Extracted {} chars text for file id={}", text.length(), file.getFileId());
                }
            } catch (Exception e) {
                log.warn("Text extraction failed for file id={}", file.getFileId(), e);
            }

            // 4. Office → PDF 预览转换
            String previewKey = null;
            String ext = file.getFileExtension() == null ? "" : file.getFileExtension().toLowerCase();
            if (OFFICE_EXTS.contains(ext)) {
                previewKey = convertOfficeToPdfAndUpload(file, local);
            }

            // 5. 更新 doc_file 元数据
            DocFile update = new DocFile();
            update.setFileId(file.getFileId());
            update.setMimeType(mime);
            if (previewKey != null) {
                update.setPreviewKey(previewKey);
            }
            fileMapper.updateById(update);

        } catch (Exception e) {
            log.error("File processing failed for id={}, name={}",
                    file.getFileId(), file.getFileName(), e);
        } finally {
            // 清理临时目录
            if (tmpDir != null) {
                try {
                    deleteRecursively(tmpDir);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 用 LibreOffice soffice CLI 把 Office 文档转成 PDF，并上传 MinIO
     *
     * @return MinIO preview key；失败返回 null
     */
    private String convertOfficeToPdfAndUpload(DocFile file, File source) {
        Path pdfPath = null;
        try {
            Path tmpDir = Files.createTempDirectory("dms-conv-");
            pdfPath = tmpDir.resolve(sanitize(file.getFileName()) + ".pdf");

            // 调用 soffice --headless --convert-to pdf
            String soffice = officeHome + File.separator + "program" + File.separator + "soffice";
            Process p = new ProcessBuilder(
                    soffice,
                    "--headless",
                    "--norestore",
                    "--convert-to", "pdf",
                    "--outdir", tmpDir.toAbsolutePath().toString(),
                    source.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                log.warn("soffice timeout for file id={}", file.getFileId());
                return null;
            }
            if (p.exitValue() != 0) {
                log.warn("soffice failed for file id={}, exit={}, out={}",
                        file.getFileId(), p.exitValue(), out);
                return null;
            }

            // PDF 输出文件名可能含原扩展名
            Path actualPdf = pdfPath;
            if (!Files.exists(actualPdf)) {
                // 尝试找同目录的 .pdf
                try (var stream = Files.list(tmpDir)) {
                    actualPdf = stream.filter(f -> f.toString().toLowerCase().endsWith(".pdf"))
                            .findFirst().orElse(null);
                }
            }
            if (actualPdf == null || !Files.exists(actualPdf)) {
                log.warn("No pdf produced for file id={}", file.getFileId());
                return null;
            }

            // 上传到 MinIO
            String objectKey = String.format("previews/%d/%d/%s.pdf",
                    file.getFileId(), System.currentTimeMillis(), UUID.randomUUID().toString().substring(0, 8));
            try (InputStream is = Files.newInputStream(actualPdf)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minIoConfig.getBucket())
                        .object(objectKey)
                        .stream(is, Files.size(actualPdf), -1)
                        .contentType("application/pdf")
                        .build());
            }
            log.info("Office converted to pdf preview: fileId={}, key={}", file.getFileId(), objectKey);
            return objectKey;

        } catch (Exception e) {
            log.error("Office→PDF conversion failed for file id={}", file.getFileId(), e);
            return null;
        } finally {
            if (pdfPath != null) {
                try {
                    deleteRecursively(pdfPath.getParent());
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String sanitize(String name) {
        if (name == null) return "file";
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
