// 模块用途：项目角色返回对象——不含逻辑删除标记和version字段
// 依赖文件：ProjectRole.java
// 修改注意：增减字段时需同步更新 ProjectRoleService 中的 toDTO 转换方法
package com.jifeng.assessment.projectrole;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ProjectRoleDTO {
    private String roleCode;
    private String roleName;
    private String description;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
