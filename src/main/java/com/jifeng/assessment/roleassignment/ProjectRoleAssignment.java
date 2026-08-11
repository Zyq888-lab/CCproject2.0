// 模块用途：项目角色分配实体——对应 project_role_assignment 表
// 依赖文件：无
// 修改注意：is_primary_pd 每个项目只能有一个为true，业务层保证唯一性
package com.jifeng.assessment.roleassignment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("project_role_assignment")
public class ProjectRoleAssignment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String projectCode;
    private String projectStage;
    private String projectRoleCode;
    private String employeeId;
    private Boolean isPrimaryPd;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}
