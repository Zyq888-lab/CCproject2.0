// 模块用途：权重校验工具——BigDecimal 权重之和是否等于100%（容差±0.001）
// 依赖文件：无（纯工具类，不依赖任何项目类）
// 修改注意：所有KPI权重校验、岗位权重校验均复用此类，确保容差一致
package com.jifeng.assessment.kpi;

import com.jifeng.assessment.common.BusinessException;

import java.math.BigDecimal;
import java.util.List;

public final class WeightValidator {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.001");
    private static final BigDecimal ONE = BigDecimal.ONE;

    private WeightValidator() {}

    // 功能：校验权重列表之和是否为 1.0（容差±0.001），不满足时抛出400
    public static void validateSumEqualsOne(List<BigDecimal> weights, String scope) {
        if (weights == null || weights.isEmpty()) {
            return;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal w : weights) {
            if (w != null) {
                sum = sum.add(w);
            }
        }
        if (sum.subtract(ONE).abs().compareTo(TOLERANCE) > 0) {
            throw new BusinessException(400,
                    scope + "指标权重之和必须为100%（当前: " + sum + "）");
        }
    }

    // 功能：校验新权重加上已有权重之和是否不超过 1.0
    public static void validateNotExceed(BigDecimal newWeight, BigDecimal existingSum, String scope) {
        BigDecimal total = existingSum.add(newWeight);
        if (total.subtract(ONE).abs().compareTo(TOLERANCE) > 0 && total.compareTo(ONE) > 0) {
            throw new BusinessException(400,
                    scope + "指标权重之和超过100%（当前已有: " + existingSum + " + 新增: " + newWeight + " = " + total + "）");
        }
    }

    // 功能：计算权重列表之和
    public static BigDecimal sum(List<BigDecimal> weights) {
        if (weights == null || weights.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal w : weights) {
            if (w != null) {
                total = total.add(w);
            }
        }
        return total;
    }
}
