// 模块用途：差异报告实体——对应 discrepancy_log 表，记录考核关系生成时的异常项
// 依赖文件：无
// 修改注意：type 为 NO_POSITION_CONFIG/NO_ASSESSOR/NO_LEADER，resolved 标记 ADMIN 是否已处理
package com.jifeng.assessment.task;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("discrepancy_log")
public class DiscrepancyLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("period_id")
    private String periodId;

    @TableField("employee_id")
    private String employeeId;

    @TableField("project_code")
    private String projectCode;

    private String type;

    private String detail;

    private Boolean resolved;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
