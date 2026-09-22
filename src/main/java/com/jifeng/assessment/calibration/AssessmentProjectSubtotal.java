// 模块用途：项目任务小计落库实体——对应 assessment_project_subtotal 表，键 (period, assessee, code, stage)
// 依赖文件：无
// 修改注意：读路径仍从 assessment_score 实时重算小计，本表作持久化/审计；改分时 upsert
package com.jifeng.assessment.calibration;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("assessment_project_subtotal")
public class AssessmentProjectSubtotal {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("period_id")
    private String periodId;

    @TableField("assessee_id")
    private String assesseeId;

    @TableField("project_code")
    private String projectCode;

    @TableField("project_stage")
    private String projectStage;

    @TableField("original_subtotal")
    private BigDecimal originalSubtotal;

    @TableField("adjusted_subtotal")
    private BigDecimal adjustedSubtotal;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
