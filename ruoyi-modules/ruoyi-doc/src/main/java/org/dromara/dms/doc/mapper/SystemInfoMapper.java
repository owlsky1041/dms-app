package org.dromara.dms.doc.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 系统信息页用的只读统计查询
 *
 * <p>这些都是运维观测用的库级信息（版本、体积、连接数、表行数），
 * 不属于任何业务表，单独放一个 Mapper 比塞进业务 Mapper 清楚。
 *
 * @author DMS
 */
@Mapper
public interface SystemInfoMapper {

    /** PostgreSQL 完整版本串，如 PostgreSQL 17.2 on x86_64-pc-linux-gnu */
    @Select("SELECT version()")
    String dbVersion();

    @Select("SELECT current_database()")
    String dbName();

    /** 当前库占用的磁盘字节数 */
    @Select("SELECT pg_database_size(current_database())")
    Long dbSizeBytes();

    /** 当前库的活跃连接数 */
    @Select("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()")
    Integer dbConnections();

    /** 允许的最大连接数（pg_settings 里是文本，取回来再转） */
    @Select("SELECT setting FROM pg_settings WHERE name = 'max_connections'")
    String dbMaxConnections();

    /**
     * 数据库进程启动时间
     *
     * <p>必须显式 ::timestamp：pg_postmaster_start_time() 返回 TIMESTAMPTZ，
     * 直接映射 java.time.LocalDateTime 会抛 "Cannot convert the column of type TIMESTAMPTZ"。
     */
    @Select("SELECT pg_postmaster_start_time()::timestamp")
    LocalDateTime dbStartTime();

    /** 业务库全部表的总体积（含索引与 TOAST） */
    @Select("SELECT COALESCE(sum(pg_total_relation_size(c.oid)), 0) "
            + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname = 'public' AND c.relkind = 'r'")
    Long dbTablesBytes();

    @Select("SELECT count(*) FROM information_schema.tables "
            + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")
    Integer dbTableCount();

    // ---------------- 业务数据量（顺便当"系统是否正常在用"的体温计） ----------------

    @Select("SELECT count(*) FROM doc_file WHERE deleted_at IS NULL")
    Integer docFileCount();

    @Select("SELECT count(*) FROM doc_folder WHERE deleted_at IS NULL")
    Integer docFolderCount();

    @Select("SELECT count(*) FROM doc_file WHERE deleted_at IS NOT NULL "
            + "UNION ALL SELECT count(*) FROM doc_folder WHERE deleted_at IS NOT NULL")
    java.util.List<Integer> recycleCounts();

    @Select("SELECT count(*) FROM doc_export_task")
    Integer exportTaskCount();

    @Select("SELECT count(*) FROM doc_audit_log")
    Integer auditLogCount();

    @Select("SELECT count(*) FROM sys_user WHERE del_flag = '0'")
    Integer sysUserCount();

    @Select("SELECT count(*) FROM sys_dept WHERE del_flag = '0'")
    Integer sysDeptCount();

    @Select("SELECT count(*) FROM sys_role WHERE del_flag = '0'")
    Integer sysRoleCount();

    /**
     * 「在用」文件数
     *
     * <p>docFileCount() 本来就是排除回收站的，这里单独再给一个名字是为了让页面上的
     * 两个数字（总数 / 在用）语义清楚，不依赖读代码的人去猜。
     */
    @Select("SELECT count(*) FROM doc_file WHERE deleted_at IS NULL")
    Integer visibleFileCount();

    @Select("SELECT count(*) FROM doc_folder WHERE deleted_at IS NULL")
    Integer visibleFolderCount();

    /** 在用文件的总字节（总量级） */
    @Select("SELECT COALESCE(sum(file_size), 0) FROM doc_file WHERE deleted_at IS NULL")
    Long totalFileBytes();

    /** 最大的那个文件有多大：排查"是不是有人传了个巨型文件"时最直接 */
    @Select("SELECT COALESCE(max(file_size), 0) FROM doc_file WHERE deleted_at IS NULL")
    Long largestFileBytes();

    @Select("SELECT count(*) FROM doc_folder_permission")
    Integer folderGrantCount();

    @Select("SELECT count(*) FROM doc_file_permission")
    Integer fileGrantCount();

    /**
     * 按扩展名统计个数与占用
     *
     * <p>库里有 file_extension（上传时就解析好了），所以按它分组最省事。
     * <b>不在这里判 MIME 归类</b>：分类规则（pdf/image/video/office/cad…）在前端
     * {@code types/doc.ts} 的 getCategory 里已经有一份，在后端再写一份必然两边漂移；
     * 这里只给"扩展名 → 个数/字节"，归类交给前端复用同一份规则。
     */
    @Select("SELECT COALESCE(NULLIF(lower(file_extension), ''), '(无扩展名)') AS ext, "
            + "count(*) AS cnt, COALESCE(sum(file_size), 0) AS bytes "
            + "FROM doc_file WHERE deleted_at IS NULL GROUP BY 1 ORDER BY cnt DESC, 1")
    java.util.List<java.util.Map<String, Object>> fileExtCounts();
}
