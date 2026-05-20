// 模块用途：项目角色实体——对应 project_role 表，role_code 为业务主键
// 依赖文件：无
// 修改注意：role_code 不可修改（业务主键），version 字段用于乐观锁
package com.jifeng.assessment.projectrole;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("project_role")
public class ProjectRole {
    @TableId
    private String roleCode;
    private String roleName;
    private String description;
    private Boolean isActive;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}
