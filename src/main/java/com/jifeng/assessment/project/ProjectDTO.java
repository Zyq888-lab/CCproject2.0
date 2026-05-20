// 模块用途：项目返回对象——不含逻辑删除标记和version字段
// 依赖文件：Project.java
// 修改注意：增减字段时需同步更新 ProjectService 中的 toDTO 转换方法
package com.jifeng.assessment.project;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ProjectDTO {
    private String projectCode;
    private String projectName;
    private String projectStage;
    private String description;
    private String status;
    private Boolean stageConfirmed;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
