// 模块用途：项目角色分配返回对象——不含逻辑删除标记和version字段
// 依赖文件：ProjectRoleAssignment.java
// 修改注意：增减字段时需同步更新 RoleAssignmentService 中的 toDTO 转换方法
package com.jifeng.assessment.roleassignment;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ProjectRoleAssignmentDTO {
    private Long id;
    private String projectCode;
    private String projectRoleCode;
    private String employeeId;
    private String employeeName;
    private Boolean isPrimaryPd;
    private LocalDateTime createdAt;
}
