// 模块用途：KPI指标DTO——任务详情返回的指标项，含已有评分回填
// 依赖文件：无
// 修改注意：kpiType 区分 PROJECT/FUNCTIONAL，kpiConfigId 多态引用对应 KPI 配置表
package com.jifeng.assessment.task;

import java.math.BigDecimal;

public record KpiIndicatorDTO(
        Long kpiConfigId,
        String kpiType,
        String indicatorName,
        BigDecimal weight,
        BigDecimal score,
        String evidenceUrl) {
}
