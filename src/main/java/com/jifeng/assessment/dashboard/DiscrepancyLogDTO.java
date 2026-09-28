// 模块用途：差异报告返回对象——在 discrepancy_log 基础上补员工姓名、项目名
// 依赖文件：DiscrepancyLog.java
// 修改注意：增减字段时需同步更新 DashboardService.toDiscrepancyDTO 转换方法
package com.jifeng.assessment.dashboard;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DiscrepancyLogDTO {
    private Long id;
    private String periodId;
    private String employeeId;
    private String employeeName;
    private String projectCode;
    private String projectStage;
    private String projectName;
    private String type;
    private String detail;
    private Boolean resolved;
    private LocalDateTime createdAt;
}
