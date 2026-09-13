package org.dromara.dms.doc.service.impl;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.dms.doc.domain.DocFile;
import org.dromara.dms.doc.mapper.DocFileMapper;
import org.dromara.dms.doc.service.ImageDimensionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 图片尺寸读取实现（只读文件头）
 *
 * @author DMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageDimensionServiceImpl implements ImageDimensionService {

    static {
        // 禁止 ImageIO 落磁盘临时文件：这里只是读尺寸，不需要缓存像素
        ImageIO.setUseCache(false);
    }

    private final MinioClient minioClient;
    private final DocFileMapper fileMapper;

    @Value("${dms.minio.bucket:dms-files}")
    private String defaultBucket;

    @Override
    public int[] readDimensions(DocFile file) {
        if (file == null || file.getStorageKey() == null) {
            return null;
        }
        String bucket = file.getStorageBucket() == null ? defaultBucket : file.getStorageBucket();
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(file.getStorageKey())
                .build());
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                // 不是 ImageIO 认识的位图（heic/RAW 等）：交给前端用默认比例兜底
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                // getWidth/getHeight 只解析文件头，不会解码整张图
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                return w > 0 && h > 0 ? new int[]{w, h} : null;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.warn("读取图片尺寸失败: fileId={}, {}", file.getFileId(), e.getMessage());
            return null;
        }
    }

    @Override
    public int[] resolve(DocFile file) {
        if (file == null) {
            return null;
        }
        Integer w = file.getWidth();
        Integer h = file.getHeight();
        if (w != null && w > 0 && h != null && h > 0) {
            return new int[]{w, h};
        }
        int[] dim = readDimensions(file);
        if (dim == null) {
            return null;
        }
        try {
            fileMapper.updateById(new DocFile()
                    .setFileId(file.getFileId())
                    .setWidth(dim[0])
                    .setHeight(dim[1]));
            // 同步到入参对象，本次返回就能用上
            file.setWidth(dim[0]);
            file.setHeight(dim[1]);
        } catch (Exception e) {
            // 写回失败不影响本次展示
            log.warn("回写图片尺寸失败: fileId={}, {}", file.getFileId(), e.getMessage());
        }
        return dim;
    }
}
