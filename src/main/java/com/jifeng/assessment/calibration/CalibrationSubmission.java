// 模块用途：项目级校准提交实体——对应 calibration_submission 表，PD 逐项目提交校准
// 依赖文件：无
// 修改注意：提交单元 = (period_id, project_code, project_stage) 唯一；重复提交更新 submitted_at（幂等）；
//   总裁退回某项目后置空 submitted_at（unsubmit），PD 需重新提交
package com.jifeng.assessment.calibration;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("calibration_submission")
public class CalibrationSubmission {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("period_id")
    private String periodId;

    @TableField("project_code")
    private String projectCode;

    @TableField("project_stage")
    private String projectStage;

    @TableField("submitted_by_employee_id")
    private String submittedByEmployeeId;

    @TableField("submitted_at")
    private LocalDateTime submittedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
