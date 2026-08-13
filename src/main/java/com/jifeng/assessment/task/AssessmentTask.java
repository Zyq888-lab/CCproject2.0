// 模块用途：考核任务实体——对应 assessment_task 表，考核人×被考核人配对，含乐观锁和逻辑删除
// 依赖文件：无
// 修改注意：task_type 为 PROJECT/FUNCTIONAL，FUNCTIONAL 时 project_code/project_stage 为 NULL
package com.jifeng.assessment.task;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("assessment_task")
public class AssessmentTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("period_id")
    private String periodId;

    @TableField("assessor_id")
    private String assessorId;

    @TableField("assessee_id")
    private String assesseeId;

    @TableField("project_code")
    private String projectCode;

    @TableField("project_stage")
    private String projectStage;

    @TableField("task_type")
    private String taskType;

    private String status;

    @TableField("return_count")
    private Integer returnCount;

    @TableField("max_returns")
    private Integer maxReturns;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
