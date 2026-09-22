// 模块用途：单项 KPI 改分响应——返回重算后的项目任务小计，供前端实时更新该行调整后总分
// 依赖文件：无
package com.jifeng.assessment.calibration;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AdjustKpiResult {

    private Long taskId;
    private BigDecimal originalSubtotal; // 原始项目任务小计（Σ score×归一化weight）
    private BigDecimal adjustedSubtotal; // 校准后项目任务小计（Σ COALESCE(calibrated_score,score)×归一化weight）
}
