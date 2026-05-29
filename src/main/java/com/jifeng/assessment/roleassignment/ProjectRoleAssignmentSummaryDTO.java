// 模块用途：项目角色分配汇总DTO——四表JOIN结果，用于跨项目汇总视图
// 依赖文件：无
// 修改注意：字段名需与SQL查询别名一致
package com.jifeng.assessment.roleassignment;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ProjectRoleAssignmentSummaryDTO {
    private Long id;
    private String projectCode;
    private String projectName;
    private String projectStage;
    private String projectStatus;
    private String roleCode;
    private String roleName;
    private String employeeId;
    private String employeeName;
    private String employeeCategory;
    private String employeePosition;
    private String orgName;
    private Boolean isPrimaryPd;
    private LocalDateTime createdAt;
}
