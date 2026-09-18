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
    private LocalDateTime calibrationSubmittedAt; // PD 提交校准时间（NULL=尚未提交）
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
        private String key;        // 分组键：project:<code> 或 functional
        private String label;      // 展示名：项目名·阶段 或 职能考核
        private int count;
        private BigDecimal avg;    // 原始分均值（0-5）
        private BigDecimal sigma;  // 总体标准差（σ=0 表示组内分数一致）
        private int outlierCount;
    }

    // 员工结果行——离群优先排序（|偏离度| 降序），列：员工/项目/原始总分/调整后总分/离群标记/操作
    @Data
    public static class Row {
        private String assesseeId;
        private String employeeName;
        private String groupKey;
        private String groupLabel;
        private BigDecimal originalScore;
        private BigDecimal adjustedScore;
        private boolean adjusted;     // 是否已改分（original ≠ adjusted）
        private BigDecimal deviation; // 偏离度（σ 单位，带符号）；σ=0 时为 null
        private boolean outlier;      // |deviation| > 1
        private String direction;     // HIGH（红↑）/ LOW（蓝↓）/ null
        private Long version;
    }
}
