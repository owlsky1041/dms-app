package org.dromara.dms.doc.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件夹实体
 *
 * <p>对应数据库表 doc_folder
 *
 * <p>关键字段：
 * <ul>
 *   <li>{@code folder_path}：物化路径 {@code /0/5/12/}，便于子树查询</li>
 *   <li>{@code deleted_at}：软删除时间，回收站和"未删除"通过 {@code WHERE deleted_at IS NULL} 区分</li>
 * </ul>
 *
 * <p>注意：本表不使用 MyBatis-Plus 的 @TableLogic 逻辑删除，因为 PostgreSQL 下
 * 软删除时间戳与 MP 默认的 0/1 标志位语义不同。我们用 Service 层显式过滤。
 *
 * @author DMS
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("doc_folder")
public class DocFolder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "folder_id", type = IdType.AUTO)
    private Long folderId;

    /** 父文件夹 ID（0=根） */
    private Long parentId;

    /** 物化路径，/0/5/12/ 形式 */
    private String folderPath;

    /** 显示名称 */
    private String folderName;

    /** 图标 */
    private String icon;

    /** 描述 */
    private String description;

    /** 排序 */
    private Integer sortOrder;

    /** 创建者（自动拥有完全控制权） */
    private Long ownerId;

    /** 所属部门（数据权限用） */
    private Long deptId;

    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;

    /** 软删除时间（NULL=未删除） */
    @TableField(select = true)
    private LocalDateTime deletedAt;

    /** Service 层使用的辅助查询条件：是否在回收站 */
    @TableField(exist = false)
    private Boolean inRecycleBin;

    /**
     * 当前用户对该文件夹的权限位（非数据库字段，列表接口里填充）
     *
     * <p>前端据此决定「下载文件夹(zip)」等入口是否可用。
     * 与 {@code DocFile.userFlags} 语义一致，缺省视为无权限。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Integer userFlags;
}
