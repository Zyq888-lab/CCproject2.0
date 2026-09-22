// 模块用途：校准矩阵响应 DTO——分布汇总带 + 离群优先排序的员工结果行
// 依赖文件：无
// 修改注意：偏离度 deviation 为「原始分偏离组均值多少个 σ」（带符号），σ=0 或组内不足 2 人时为 null；
//   离群判定 |deviation| > 1；direction 仅 HIGH/LOW 两种，前端红↑蓝↓
package com.jifeng.assessment.calibration;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CalibrationMatrixResponse {

    private String periodId;
    private String periodName;
    private LocalDateTime calibrationSubmittedAt; // 周期级派生提交时间（本周期所有涉及项目均已提交后非空）
    private boolean submitted; // 当前 PD 名下项目是否全部已提交（项目级提交粒度，前端据此控制提交按钮）
    private boolean hasReturned; // 当前 PD 名下项目是否存在 RETURNED 确认行（总裁已退回，PD 需重新提交）
    private int unsubmittedCount;
    private List<Unsubmitted> unsubmitted;
    private List<GroupSummary> summary;
    private List<Row> rows;

    // 未提交员工——存在 PENDING/IN_PROGRESS 任务的去重员工，矩阵底部暗行展示（结果尚未纳入校准）
    @Data
    public static class Unsubmitted {
        private String assesseeId;
        private String employeeName;
    }

    // 分布汇总带——按「项目」或「职能」分组的人均分/σ/离群数，供 PD 快速定位离群项目/职能
    @Data
    public static class GroupSummary {
        private String key;        // 分组键：project:<code>|<stage> 或 functional
        private String label;      // 展示名：项目名·阶段 或 职能考核
        private int count;
        private BigDecimal avg;    // 原始分均值（0-5）
        private BigDecimal sigma;  // 总体标准差（σ=0 表示组内分数一致）
        private int outlierCount;
    }

    // 员工结果行——离群优先排序（|偏离度| 降序），列：员工/项目/原始总分/调整后总分/离群标记/操作
    // 原始/调整后总分现取「项目任务小计」（由 assessment_score 按归一化权重重算），不再取周期级 composite
    @Data
    public static class Row {
        private String assesseeId;
        private String employeeName;
        private String groupKey;
        private String groupLabel;
        private BigDecimal originalScore;  // 原始项目任务小计
        private BigDecimal adjustedScore;  // 校准后项目任务小计
        private boolean adjusted;          // 是否已改分（original ≠ adjusted）
        private BigDecimal deviation;      // 偏离度（σ 单位，带符号）；σ=0 时为 null
        private boolean outlier;           // |deviation| > 1
        private String direction;          // HIGH（红↑）/ LOW（蓝↓）/ null
        private Long version;
        private String confirmationStatus; // 总裁确认状态：PENDING/APPROVED/RETURNED（无确认行时为 null）
        private String returnReason;       // 总裁退回意见（仅 RETURNED 时有值）
        private Long taskId;               // 该行绑定的任务 id（单项改分入口用；职能组取首个职能任务）
        private List<Kpi> kpis;            // 该任务逐 KPI 明细（指标名称/权重/得分/评估人/证据）
    }

    // 逐 KPI 明细项——矩阵每行带出，供 PD 校准抽屉编辑、总裁确认抽屉只读展示
    @Data
    public static class Kpi {
        private Long kpiConfigId;
        private String kpiType;        // PROJECT/FUNCTIONAL
        private String indicatorName;  // 指标名称
        private BigDecimal weight;     // 配置权重（原始，未归一化）
        private BigDecimal originalScore;   // 评估人原始分
        private BigDecimal calibratedScore; // PD 校准覆盖分（NULL=未校准）
        private BigDecimal score;           // 有效分 = COALESCE(calibratedScore, originalScore)
        private String assessorName;        // 评估人姓名
        private String evidenceUrl;         // 证据
    }
}
