package org.dromara.dms.doc.service;

import org.dromara.dms.doc.domain.DocFile;

/**
 * 图片尺寸读取
 *
 * <p>PhotoSwipe 这类查看器在打开图片前就需要知道宽高：它用宽高算初始缩放与占位框，
 * 拿不到就只能给一个瞎猜的值，打开时会先按错误比例显示再"跳"一下。
 * 而 {@code doc_file.width/height} 之前只有视频填过（ffprobe 探测的），图片一直是空的。
 *
 * <p>这里按需读取：只在真正要展示图片时读一次文件头，然后写回库，
 * 不改上传链路（上传是主流程，能不动就不动）。
 *
 * @author DMS
 */
public interface ImageDimensionService {

    /**
     * 取图片宽高，取不到返回 null
     *
     * <p>只读文件头（JPEG 的 SOF、PNG 的 IHDR 都在文件开头），不会把整张图解出来。
     */
    int[] readDimensions(DocFile file);

    /**
     * 取宽高，库里没有就现读一次并写回
     *
     * @return [width, height]，读不出来返回 null
     */
    int[] resolve(DocFile file);
}
