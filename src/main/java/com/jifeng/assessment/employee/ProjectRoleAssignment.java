package com.jifeng.assessment.employee;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("project_role_assignment")
public class ProjectRoleAssignment {
    @TableId
    private Long id;
    private String projectCode;
    private String projectRoleCode;
    private String employeeId;
    private Boolean isPrimaryPd;
    private Integer deleted;
}
