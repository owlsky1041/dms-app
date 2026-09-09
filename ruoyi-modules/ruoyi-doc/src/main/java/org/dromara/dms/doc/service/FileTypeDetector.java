package org.dromara.dms.doc.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Apache Tika 文件类型识别
 *
 * @author DMS
 */
@Slf4j
@Service
public class FileTypeDetector {

    private final Tika tika = new Tika();

    /**
     * 识别文件 MIME 类型（基于文件内容 + 文件名）
     */
    public String detect(File file) {
        try (InputStream is = new FileInputStream(file)) {
            return tika.detect(is, file.getName());
        } catch (IOException e) {
            log.warn("Failed to detect MIME for {}", file.getName(), e);
            return "application/octet-stream";
        }
    }

    /**
     * 仅基于文件名猜测
     */
    public String detectByName(String filename) {
        return tika.detect(filename);
    }

    /**
     * 提取文本（PDF / Office / 文本 / 图片 OCR 都用这一个方法）
     *
     * @return 提取出的纯文本；失败时返回空串
     */
    public String extractText(File file) {
        try {
            return tika.parseToString(file);
        } catch (Exception e) {
            log.warn("Tika extractText failed for {}", file.getName(), e);
            return "";
        }
    }
}
