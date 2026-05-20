// 模块用途：考核周期实体——对应 assessment_period 表，status: INIT/ONGOING/CALIBRATING/COMPLETED
// 依赖文件：无
// 修改注意：period_id 为业务主键（服务端生成），同一时间只能有一个非COMPLETED周期
package com.jifeng.assessment.period;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("assessment_period")
public class AssessmentPeriod {

    @TableId
    private String periodId;

    @TableField("period_name")
    private String periodName;

    @TableField("start_date")
    private LocalDate startDate;

    @TableField("end_date")
    private LocalDate endDate;

    private String status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
