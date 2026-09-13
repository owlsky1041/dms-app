package org.dromara.dms.doc.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 异步打包下载任务（doc_export_task）
 *
 * @author DMS
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("doc_export_task")
public class DocExportTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 任务状态 */
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";
    public static final String EXPIRED = "EXPIRED";

    @TableId(value = "task_id")
    private Long taskId;

    /** 发起人 */
    private Long userId;

    /** ZIP 顶层名（同时也是下载文件名前缀） */
    private String rootName;

    /** 入口资源类型：FOLDER / SELECTION */
    private String resourceType;

    /** 入口资源 ID（单个文件夹时为该文件夹） */
    private Long resourceId;

    private String status;

    /** 计划打包文件数 */
    private Integer fileCount;

    /** 计划未压缩总字节数 */
    private Long totalBytes;

    /** 已打包文件数（进度） */
    private Integer doneFiles;

    /** 已打包字节数（进度） */
    private Long doneBytes;

    /** 生成的 ZIP 实际大小 */
    private Long zipBytes;

    /** 因无下载权限被跳过的文件数 */
    private Integer skippedCount;

    /** ZIP 落盘路径 */
    private String filePath;

    private String errorMsg;

    /** 打包计划 JSON（目录条目 + 文件条目，创建时冻结） */
    private String planJson;

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;

    /** 过期时间，到点由定时任务删除文件 */
    private LocalDateTime expireTime;

    private Integer downloadCount;

    private LocalDateTime lastDownloadTime;
}
