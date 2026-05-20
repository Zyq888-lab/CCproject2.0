// 模块用途：项目实体——对应 project 表，project_code 为业务主键
// 依赖文件：无
// 修改注意：project_code 不可修改（业务主键），version 字段用于乐观锁
package com.jifeng.assessment.project;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("project")
public class Project {
    @TableId
    @NotBlank
    private String projectCode;
    @NotBlank
    private String projectName;
    @NotBlank
    private String projectStage;
    private String description;
    private String status;
    private Boolean stageConfirmed;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String confirmedBy;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime confirmedAt;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}
