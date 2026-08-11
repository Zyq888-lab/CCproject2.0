package com.jifeng.assessment.roleassignment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

@Mapper
public interface ProjectRoleAssignmentMapper extends BaseMapper<ProjectRoleAssignment> {

    @Select("<script>"
            + "SELECT a.id, a.project_code, p.project_name, p.project_stage, p.status AS project_status, "
            + "a.project_role_code AS role_code, r.role_name, a.employee_id, e.name AS employee_name, "
            + "e.category AS employee_category, e.position AS employee_position, e.org_name, "
            + "a.is_primary_pd, a.created_at "
            + "FROM project_role_assignment a "
            + "JOIN project p ON p.project_code = a.project_code AND p.project_stage = a.project_stage AND p.deleted = 0 "
            + "JOIN project_role r ON r.role_code = a.project_role_code AND r.deleted = 0 "
            + "JOIN employee e ON e.employee_id = a.employee_id AND e.deleted = 0 "
            + "WHERE a.deleted = 0 "
            + "<if test='projectCode != null and projectCode != \"\"'>AND a.project_code = #{projectCode} </if>"
            + "<if test='projectStage != null and projectStage != \"\"'>AND p.project_stage = #{projectStage} </if>"
            + "<if test='roleCode != null and roleCode != \"\"'>AND a.project_role_code = #{roleCode} </if>"
            + "<if test='employeeId != null and employeeId != \"\"'>AND a.employee_id LIKE CONCAT('%', #{employeeId}, '%') </if>"
            + "<if test='isPrimaryPd != null'>AND a.is_primary_pd = #{isPrimaryPd} </if>"
            + "ORDER BY p.project_code, a.id"
            + "</script>")
    Page<ProjectRoleAssignmentSummaryDTO> selectSummaryPage(
            Page<ProjectRoleAssignmentSummaryDTO> page,
            @Param("projectCode") String projectCode,
            @Param("projectStage") String projectStage,
            @Param("roleCode") String roleCode,
            @Param("employeeId") String employeeId,
            @Param("isPrimaryPd") Boolean isPrimaryPd);
}
