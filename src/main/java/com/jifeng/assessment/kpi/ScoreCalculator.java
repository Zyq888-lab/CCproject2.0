// 模块用途：算分公式引擎——纯函数实现绩效考核得分计算（D11）
// 依赖文件：无（纯计算，不依赖任何项目类）
// 修改注意：公式来源于设计文档 design-绩效考核系统-20260518.md §最终得分计算公式
//   验证用例：4.2×0.6 + 3.8×0.4 = 4.04 → 4.04×0.7 + 4.0×0.3 = 3.828
package com.jifeng.assessment.kpi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class ScoreCalculator {

    private ScoreCalculator() {}

    // 功能：加权求和——Σ(score_i × weight_i)，用于单项目KPI得分或职能KPI得分
    // 示例：[4.2, 3.8] × [0.6, 0.4] = 4.2×0.6 + 3.8×0.4 = 4.04
    public static BigDecimal weightedSum(List<BigDecimal> scores, List<BigDecimal> weights) {
        if (scores == null || weights == null || scores.size() != weights.size()) {
            throw new IllegalArgumentException("分数和权重列表长度必须一致");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < scores.size(); i++) {
            BigDecimal score = scores.get(i);
            BigDecimal weight = weights.get(i);
            if (score != null && weight != null) {
                sum = sum.add(score.multiply(weight));
            }
        }
        return sum.setScale(4, RoundingMode.HALF_UP);
    }

    // 功能：项目考核加权得分——Σ(项目KPI加权得分_i × 项目投入比重_i)
    // 示例：[4.04] × [1.0] = 4.04（单项目），或 [4.2, 3.8] × [0.6, 0.4] = 4.04（多项目）
    public static BigDecimal projectCompositeScore(List<BigDecimal> projectScores, List<BigDecimal> participationRates) {
        return weightedSum(projectScores, participationRates);
    }

    // 功能：最终得分——项目考核加权得分 × 岗位项目权重 + 职能考核得分 × 岗位职能权重
    // 示例：4.04 × 0.7 + 4.0 × 0.3 = 3.828
    public static BigDecimal finalScore(BigDecimal projectScore, BigDecimal projectWeight,
                                        BigDecimal funcScore, BigDecimal funcWeight) {
        return projectScore.multiply(projectWeight)
                .add(funcScore.multiply(funcWeight))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
