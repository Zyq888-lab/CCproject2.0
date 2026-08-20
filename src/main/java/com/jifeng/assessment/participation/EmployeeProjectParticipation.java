// 模块用途：员工项目参与实体——对应 employee_project_participation 表，含乐观锁和逻辑删除
// 依赖文件：无
// 修改注意：participationRate/suggestedRate 为百分制 DECIMAL(5,2)，前端传整数(1-100)
package com.jifeng.assessment.participation;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("employee_project_participation")
public class EmployeeProjectParticipation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("employee_id")
    private String employeeId;

    @TableField("project_code")
    private String projectCode;

    @TableField("project_stage")
    private String projectStage;

    @TableField("participation_rate")
    private BigDecimal participationRate;

    @TableField(value = "suggested_rate", updateStrategy = FieldStrategy.IGNORED)
    private BigDecimal suggestedRate;

    @TableField(value = "approval_comment", updateStrategy = FieldStrategy.IGNORED)
    private String approvalComment;

    private String status;

    @TableField(value = "approved_by", updateStrategy = FieldStrategy.IGNORED)
    private String approvedBy;

    @TableField(value = "approved_at", updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime approvedAt;

    @TableField("period_id")
    private String periodId;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
