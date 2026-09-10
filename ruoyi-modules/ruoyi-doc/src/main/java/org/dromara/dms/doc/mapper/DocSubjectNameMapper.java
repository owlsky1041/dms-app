package org.dromara.dms.doc.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.dms.doc.dto.IdNameRow;

import java.util.Collection;
import java.util.List;

/**
 * 主体名称查询 Mapper
 *
 * <p>界面展示需要「真实姓名」而不是雪花 ID：文件/文件夹的创建者、权限主体等。
 * 这里直接读取 RuoYi 的用户/角色/部门表（同库），不使用 RuoYi 服务层，
 * 以免受其数据权限拦截器影响导致部分名称解析不出来。
 *
 * <p>说明：不按 del_flag 过滤 —— 创建者可能已被删除，此时仍需显示其姓名，
 * 否则界面只能退回显示一长串 ID。
 *
 * @author DMS
 */
@Mapper
public interface DocSubjectNameMapper {

    /** 用户真实姓名（sys_user.nick_name） */
    @Select("<script>" +
            "SELECT user_id AS id, nick_name AS name FROM sys_user WHERE user_id IN " +
            "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>" +
            "</script>")
    List<IdNameRow> selectUserNames(@Param("ids") Collection<Long> ids);

    /** 角色名称 */
    @Select("<script>" +
            "SELECT role_id AS id, role_name AS name FROM sys_role WHERE role_id IN " +
            "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>" +
            "</script>")
    List<IdNameRow> selectRoleNames(@Param("ids") Collection<Long> ids);

    /** 部门名称 */
    @Select("<script>" +
            "SELECT dept_id AS id, dept_name AS name FROM sys_dept WHERE dept_id IN " +
            "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>" +
            "</script>")
    List<IdNameRow> selectDeptNames(@Param("ids") Collection<Long> ids);
}
