package org.dromara.dms.doc.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** 视频扩展名：统一走「取海报图 + 探测时长 + 必要时转 mp4」 */
    private static final Set<String> VIDEO_EXTS = Set.of(
            "mp4", "m4v", "mov", "avi", "mkv", "wmv", "flv", "webm",
            "mpg", "mpeg", "3gp", "ts", "rmvb", "rm", "ogv");

    /** 浏览器能直接播放的容器 */
    private static final Set<String> BROWSER_CONTAINERS = Set.of("mp4", "m4v", "mov", "webm", "ogv");

    /** 浏览器能直接播放的视频编码 */
    private static final Set<String> BROWSER_VIDEO_CODECS = Set.of("h264", "vp8", "vp9", "av1", "theora");

    /** 浏览器能直接播放的音频编码（none 表示无音轨，也应允许） */
    private static final Set<String> BROWSER_AUDIO_CODECS = Set.of("aac", "mp3", "opus", "vorbis", "none", "");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FileTypeDetector detector;
    private final DocFileMapper fileMapper;
    private final DocFileTextMapper fileTextMapper;
    private final MinioClient minioClient;
    private final MinIoConfig minIoConfig;

    @Value("${dms.office.home:/usr/lib/libreoffice}")
    private String officeHome;

    @Value("${dms.video.ffmpeg-bin:ffmpeg}")
    private String ffmpegBin;

    @Value("${dms.video.ffprobe-bin:ffprobe}")
    private String ffprobeBin;

    @Value("${dms.video.preview-bitrate-kbps:1500}")
    private int previewBitrateKbps;

    @Value("${dms.video.transcode-timeout-seconds:1800}")
    private int transcodeTimeoutSeconds;

    @Value("${dms.video.max-transcode-size-mb:2048}")
    private long maxTranscodeSizeMb;

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

            // 5. 视频：取海报图 + 探测时长尺寸 + 非浏览器友好格式转 mp4 预览版
            String posterKey = null;
            Long durationMs = null;
            Integer videoWidth = null;
            Integer videoHeight = null;
            boolean isVideo = VIDEO_EXTS.contains(ext) || (mime != null && mime.startsWith("video/"));
            if (isVideo) {
                VideoMeta meta = probeVideo(local);
                if (meta != null) {
                    durationMs = meta.durationMs;
                    videoWidth = meta.width;
                    videoHeight = meta.height;
                    posterKey = extractPoster(file, local, meta.durationMs);
                    if (!meta.browserPlayable) {
                        log.info("视频格式浏览器不可直接播放，转 mp4 预览: fileId={}, container={}, v={}, a={}",
                                file.getFileId(), meta.container, meta.videoCodec, meta.audioCodec);
                        previewKey = transcodeToMp4(file, local);
                    }
                }
            }

            // 6. 更新 doc_file 元数据
            DocFile update = new DocFile();
            update.setFileId(file.getFileId());
            update.setMimeType(mime);
            if (previewKey != null) {
                update.setPreviewKey(previewKey);
            }
            if (posterKey != null) {
                update.setThumbnailKey(posterKey);
            }
            if (durationMs != null) {
                update.setDurationMs(durationMs);
            }
            if (videoWidth != null && videoHeight != null) {
                update.setWidth(videoWidth);
                update.setHeight(videoHeight);
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


    // ==================================================================
    // 视频处理（ffprobe 探测 / ffmpeg 取海报 + 转码）
    // ==================================================================

    /** 视频探测结果 */
    private static final class VideoMeta {
        long durationMs;
        Integer width;
        Integer height;
        String container;
        String videoCodec;
        String audioCodec;
        /** 浏览器能否直接播放，不能则转 mp4 */
        boolean browserPlayable;
    }

    /**
     * 用 ffprobe 读取时长、尺寸与编码，并判断浏览器能否直接播放
     *
     * @return 探测失败（ffprobe 缺失/文件损坏）时返回 null，调用方跳过视频处理
     */
    private VideoMeta probeVideo(File source) {
        try {
            Process p = new ProcessBuilder(
                    ffprobeBin, "-v", "error",
                    "-print_format", "json",
                    "-show_format", "-show_streams",
                    source.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("ffprobe 超时: {}", source.getName());
                return null;
            }
            if (p.exitValue() != 0) {
                log.warn("ffprobe 失败 exit={}: {}", p.exitValue(), out);
                return null;
            }

            VideoMeta meta = new VideoMeta();
            JsonNode root = JSON.readTree(out);
            JsonNode format = root.path("format");
            // format.duration 单位是秒（浮点），换算成毫秒
            double seconds = format.path("duration").asDouble(0d);
            meta.durationMs = (long) Math.round(seconds * 1000);
            meta.container = shortName(format.path("format_name").asText(""));

            for (JsonNode st : root.path("streams")) {
                String type = st.path("codec_type").asText("");
                if ("video".equals(type) && meta.width == null) {
                    meta.width = st.path("width").asInt(0);
                    meta.height = st.path("height").asInt(0);
                    meta.videoCodec = shortName(st.path("codec_name").asText(""));
                } else if ("audio".equals(type) && meta.audioCodec == null) {
                    meta.audioCodec = shortName(st.path("codec_name").asText(""));
                }
            }
            if (meta.videoCodec == null) {
                meta.videoCodec = "";
            }
            if (meta.audioCodec == null) {
                meta.audioCodec = "";
            }
            // mov 容器浏览器多半能放 h264/aac，但为稳妥仍按 mp4/webm 判断
            meta.browserPlayable = BROWSER_CONTAINERS.contains(meta.container)
                    && BROWSER_VIDEO_CODECS.contains(meta.videoCodec)
                    && BROWSER_AUDIO_CODECS.contains(meta.audioCodec);
            log.info("视频探测: file={}, 时长={}ms, {}x{}, 容器={}, 视频={}, 音频={}, 可直接播放={}",
                    source.getName(), meta.durationMs, meta.width, meta.height,
                    meta.container, meta.videoCodec, meta.audioCodec, meta.browserPlayable);
            return meta;
        } catch (Exception e) {
            log.warn("视频探测失败（若未安装 ffprobe 可忽略）: {}", source.getName(), e);
            return null;
        }
    }

    /** format_name 可能是 "mov,mp4,m4a,3gp,3g2,mj2" 这种列表，取第一个 */
    private String shortName(String formatName) {
        if (formatName == null || formatName.isBlank()) {
            return "";
        }
        int comma = formatName.indexOf(',');
        return (comma > 0 ? formatName.substring(0, comma) : formatName).trim().toLowerCase();
    }

    /**
     * 抽取视频海报图（缩略图）
     *
     * <p>取第 1 秒或 10% 时长处的帧（取较小者），避免片头黑屏；宽度限制在 1280 以内。
     *
     * @return MinIO 对象 key；失败返回 null
     */
    private String extractPoster(DocFile file, File source, long durationMs) {
        Path outDir = null;
        try {
            outDir = Files.createTempDirectory("dms-poster-");
            Path jpg = outDir.resolve("poster.jpg");
            long seekMs = Math.max(0, Math.min(1000L, durationMs / 10));

            Process p = new ProcessBuilder(
                    ffmpegBin, "-y",
                    "-ss", String.format("%.3f", seekMs / 1000.0),
                    "-i", source.getAbsolutePath(),
                    "-frames:v", "1",
                    "-vf", "scale='min(1280,iw)':-2",
                    "-q:v", "3",
                    jpg.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("抽帧超时: fileId={}", file.getFileId());
                return null;
            }
            if (p.exitValue() != 0 || !Files.exists(jpg) || Files.size(jpg) == 0) {
                log.warn("抽帧失败 exit={}: {}", p.exitValue(), out);
                return null;
            }

            String objectKey = String.format("thumbs/%d/%d/%s.jpg",
                    file.getFileId(), System.currentTimeMillis(),
                    UUID.randomUUID().toString().substring(0, 8));
            try (InputStream is = Files.newInputStream(jpg)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minIoConfig.getBucket())
                        .object(objectKey)
                        .stream(is, Files.size(jpg), -1)
                        .contentType("image/jpeg")
                        .build());
            }
            log.info("视频海报已生成: fileId={}, key={}", file.getFileId(), objectKey);
            return objectKey;
        } catch (Exception e) {
            log.warn("生成视频海报失败: fileId={}", file.getFileId(), e);
            return null;
        } finally {
            if (outDir != null) {
                try {
                    deleteRecursively(outDir);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 把视频转成浏览器可直接播放的 mp4（H.264 + AAC，faststart）
     *
     * <p>原文件保持不动，转码结果只作为预览版（preview_key）；下载仍然拿原件。
     *
     * @return MinIO 对象 key；失败或超限返回 null
     */
    private String transcodeToMp4(DocFile file, File source) {
        Path outDir = null;
        try {
            long sizeMb = source.length() / (1024 * 1024);
            if (maxTranscodeSizeMb > 0 && sizeMb > maxTranscodeSizeMb) {
                log.warn("视频超过 {}MB，跳过转码预览: fileId={}, size={}MB",
                        maxTranscodeSizeMb, file.getFileId(), sizeMb);
                return null;
            }
            outDir = Files.createTempDirectory("dms-trans-");
            Path mp4 = outDir.resolve("preview.mp4");

            int maxRate = Math.max(200, previewBitrateKbps);
            Process p = new ProcessBuilder(
                    ffmpegBin, "-y",
                    "-i", source.getAbsolutePath(),
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "23",
                    "-maxrate", maxRate + "k",
                    "-bufsize", (maxRate * 2) + "k",
                    // yuv420p 是各浏览器/硬件解码器都接受的像素格式
                    "-pix_fmt", "yuv420p",
                    "-vf", "scale='min(1920,iw)':-2",
                    "-c:a", "aac", "-b:a", "128k", "-ac", "2",
                    // faststart 把索引放到文件头，边下边播不用等整段下载
                    "-movflags", "+faststart",
                    mp4.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            // 转码可能很久，必须读完输出流否则进程会因管道写满而卡死
            String out;
            try (InputStream is = p.getInputStream()) {
                out = new String(is.readAllBytes());
            }
            if (!p.waitFor(transcodeTimeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("转码超时（{}s），放弃预览版: fileId={}", transcodeTimeoutSeconds, file.getFileId());
                return null;
            }
            if (p.exitValue() != 0 || !Files.exists(mp4) || Files.size(mp4) == 0) {
                log.warn("转码失败 exit={}: fileId={}, tail={}",
                        p.exitValue(), file.getFileId(),
                        out.length() > 800 ? out.substring(out.length() - 800) : out);
                return null;
            }

            String objectKey = String.format("previews/%d/%d/%s.mp4",
                    file.getFileId(), System.currentTimeMillis(),
                    UUID.randomUUID().toString().substring(0, 8));
            try (InputStream is = Files.newInputStream(mp4)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minIoConfig.getBucket())
                        .object(objectKey)
                        .stream(is, Files.size(mp4), -1)
                        .contentType("video/mp4")
                        .build());
            }
            log.info("视频转码完成: fileId={}, key={}, 原大小={}MB, 预览大小={}KB",
                    file.getFileId(), objectKey, sizeMb, Files.size(mp4) / 1024);
            return objectKey;
        } catch (Exception e) {
            log.error("视频转码失败: fileId={}", file.getFileId(), e);
            return null;
        } finally {
            if (outDir != null) {
                try {
                    deleteRecursively(outDir);
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
