package org.dromara.dms.doc.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 上传会话状态
 *
 * @author DMS
 */
@Getter
@AllArgsConstructor
public enum UploadStatus {

    UPLOADING(0, "上传中"),
    COMPLETED(1, "已完成"),
    FAILED(2, "失败"),
    CANCELLED(3, "已取消"),
    INSTANT(4, "秒传");

    private final int code;
    private final String description;

    public static UploadStatus of(int code) {
        for (UploadStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown UploadStatus: " + code);
    }
}
