// 模块用途：员工考核结果响应 DTO——大字结果分(adjusted_score) + 原始分/差额/原因 + KPI 明细 + 凭证 fallback
// 依赖文件：无
// 修改注意：结果分取 adjusted_score（D3）；adjusted=true 时前端并排展示「原始分→调整分 + 差额 + 原因」；
//   KPI 明细来自 assessment_score 指标分行（非 composite），凭证列为 null 时前端回退「凭证暂不可用」
package com.jifeng.assessment.result;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class EmployeeResultResponse {

    private String periodId;
    private String periodName;
    private String assesseeId;
    private String employeeName;
    private BigDecimal originalScore;
    private BigDecimal adjustedScore;
    private boolean adjusted;      // 是否已改分（original ≠ adjusted）
    private BigDecimal delta;      // adjusted - original（仅 adjusted=true 时有值）
    private String adjustReason;   // 最新一次改分原因
    private List<KpiDetail> kpis;

    // KPI 明细行——指标名/权重/得分/评估人/凭证
    @Data
    public static class KpiDetail {
        private String kpiType;    // PROJECT / FUNCTIONAL
        private String kpiName;
        private BigDecimal weight;
        private BigDecimal score;
        private String assessorName;
        private String evidenceUrl; // 为 null 时前端回退「凭证暂不可用」
    }
}
