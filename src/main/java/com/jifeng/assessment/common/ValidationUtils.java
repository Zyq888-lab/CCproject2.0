package com.jifeng.assessment.common;

import java.math.BigDecimal;

public final class ValidationUtils {

    private ValidationUtils() {}

    /** 浮点容差（D11） */
    public static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.001");

    /**
     * 校验权重之和是否为 1.00（容忍 ±0.001 浮点误差）
     */
    public static boolean isWeightSumValid(BigDecimal... weights) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal w : weights) {
            if (w != null) {
                sum = sum.add(w);
            }
        }
        return sum.subtract(BigDecimal.ONE).abs().compareTo(WEIGHT_TOLERANCE) <= 0;
    }

    /**
     * 计算所有权重之和
     */
    public static BigDecimal sumWeights(BigDecimal... weights) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal w : weights) {
            if (w != null) {
                sum = sum.add(w);
            }
        }
        return sum;
    }
}
