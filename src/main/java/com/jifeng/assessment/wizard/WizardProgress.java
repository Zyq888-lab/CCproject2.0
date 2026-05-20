// 模块用途：配置向导进度实体——对应 wizard_progress 表
// 依赖文件：无
// 修改注意：user_id 有唯一约束 uk_wizard_user，completed_steps 逗号分隔
package com.jifeng.assessment.wizard;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wizard_progress")
public class WizardProgress {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("current_step")
    private Integer currentStep;

    @TableField("completed_steps")
    private String completedSteps;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
