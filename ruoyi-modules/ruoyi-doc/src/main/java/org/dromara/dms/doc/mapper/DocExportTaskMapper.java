package org.dromara.dms.doc.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.dms.doc.domain.DocExportTask;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步打包任务 Mapper
 *
 * @author DMS
 */
@Mapper
public interface DocExportTaskMapper extends BaseMapperPlus<DocExportTask, DocExportTask> {

    /**
     * 取待执行任务
     */
    @Select("SELECT * FROM doc_export_task WHERE status = 'PENDING' ORDER BY task_id LIMIT #{limit}")
    List<DocExportTask> selectPending(@Param("limit") int limit);

    /**
     * 抢占任务：把 PENDING 改成 RUNNING
     *
     * <p>用「带状态条件的 UPDATE」做乐观占位，多条调度线程同时跑也只有一个能抢到，
     * 不需要额外加分布式锁。
     *
     * @return 1 表示抢到，0 表示被别的线程抢先
     */
    @Update("UPDATE doc_export_task SET status = 'RUNNING', start_time = #{now} "
          + "WHERE task_id = #{taskId} AND status = 'PENDING'")
    int markRunning(@Param("taskId") Long taskId, @Param("now") LocalDateTime now);

    /**
     * 只更新进度（高频调用，不碰其它字段）
     */
    @Update("UPDATE doc_export_task SET done_files = #{doneFiles}, done_bytes = #{doneBytes} WHERE task_id = #{taskId}")
    int updateProgress(@Param("taskId") Long taskId,
                       @Param("doneFiles") int doneFiles,
                       @Param("doneBytes") long doneBytes);

    /**
     * 进程重启后，把「卡住的 RUNNING」重新排队
     *
     * <p>没有这一步，应用重启会让正在打包的任务永远停在 RUNNING。
     *
     * @return 重置的任务数
     */
    @Update("UPDATE doc_export_task SET status = 'PENDING', start_time = NULL, error_msg = '任务被中断，已自动重试' "
          + "WHERE status = 'RUNNING' AND start_time < #{before}")
    int requeueStale(@Param("before") LocalDateTime before);

    /**
     * 超过过期时间仍未下载成功的任务（含文件待删）
     */
    @Select("SELECT * FROM doc_export_task WHERE status = 'SUCCESS' AND expire_time IS NOT NULL "
          + "AND expire_time < #{now} ORDER BY task_id LIMIT #{limit}")
    List<DocExportTask> selectExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 统计某用户进行中的任务数（用于限制并发提交）
     */
    @Select("SELECT COUNT(*) FROM doc_export_task WHERE user_id = #{userId} "
          + "AND status IN ('PENDING','RUNNING')")
    int countActiveByUser(@Param("userId") Long userId);
}
