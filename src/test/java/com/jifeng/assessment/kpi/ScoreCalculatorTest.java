// 模块用途：ScoreCalculator 算分公式参数化测试——10个用例 ±0.001容差（T31/D11）
// 依赖文件：ScoreCalculator.java
// 修改注意：参数来源见 design-绩效考核系统-20260518.md §最终得分计算公式
package com.jifeng.assessment.kpi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ScoreCalculatorTest {

    // 容差：±0.001（T31要求）
    private static final BigDecimal TOLERANCE = new BigDecimal("0.001");

    private static void assertScoreEquals(BigDecimal expected, BigDecimal actual) {
        assertTrue(expected.subtract(actual).abs().compareTo(TOLERANCE) <= 0,
                () -> "Expected " + expected + " ±0.001 but got " + actual);
    }

    // ========================================
    // 10个参数化用例（满足T31要求）
    // ========================================

    static Stream<Arguments> weightedSumCases() {
        return Stream.of(
            // 用例1：单指标（满分5×权重1.0=5.0）
            Arguments.of("用例1-单指标满分",
                List.of(new BigDecimal("5.0")),
                List.of(new BigDecimal("1.0000")),
                new BigDecimal("5.0000")),

            // 用例2：两个指标（设计文档示例 4.2×0.6 + 3.8×0.4 = 4.04）
            Arguments.of("用例2-双指标标准",
                List.of(new BigDecimal("4.2"), new BigDecimal("3.8")),
                List.of(new BigDecimal("0.6000"), new BigDecimal("0.4000")),
                new BigDecimal("4.0400")),

            // 用例3：三个等权重指标（3.0×0.33 + 4.0×0.33 + 5.0×0.34 = 4.01）
            Arguments.of("用例3-三等权重",
                List.of(new BigDecimal("3.0"), new BigDecimal("4.0"), new BigDecimal("5.0")),
                List.of(new BigDecimal("0.3300"), new BigDecimal("0.3300"), new BigDecimal("0.3400")),
                new BigDecimal("4.0100")),

            // 用例4：零分指标
            Arguments.of("用例4-含零分",
                List.of(new BigDecimal("5.0"), new BigDecimal("0.0")),
                List.of(new BigDecimal("0.5000"), new BigDecimal("0.5000")),
                new BigDecimal("2.5000")),

            // 用例5：权重为0的指标不影响结果
            Arguments.of("用例5-零权重",
                List.of(new BigDecimal("4.0"), new BigDecimal("5.0")),
                List.of(new BigDecimal("1.0000"), new BigDecimal("0.0000")),
                new BigDecimal("4.0000"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weightedSumCases")
    void weightedSumParameterized(String name, List<BigDecimal> scores,
                                   List<BigDecimal> weights, BigDecimal expected) {
        BigDecimal result = ScoreCalculator.weightedSum(scores, weights);
        assertScoreEquals(expected, result);
    }

    static Stream<Arguments> finalScoreCases() {
        return Stream.of(
            // 用例6：设计文档示例 — 4.04×0.7 + 4.0×0.3 = 4.028
            Arguments.of("用例6-设计文档示例",
                new BigDecimal("4.0400"), new BigDecimal("0.7000"),
                new BigDecimal("4.0000"), new BigDecimal("0.3000"),
                new BigDecimal("4.0280")),

            // 用例7：纯项目制（项目权重100%）— 4.5×1.0 + 0×0 = 4.5
            Arguments.of("用例7-纯项目制",
                new BigDecimal("4.5000"), new BigDecimal("1.0000"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("4.5000")),

            // 用例8：纯职能制（职能权重100%）— 0×0 + 3.6×1.0 = 3.6
            Arguments.of("用例8-纯职能制",
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3.6000"), new BigDecimal("1.0000"),
                new BigDecimal("3.6000")),

            // 用例9：边界值—项目满分5.0×0.5 + 职能满分5.0×0.5 = 5.0
            Arguments.of("用例9-双满分各半",
                new BigDecimal("5.0000"), new BigDecimal("0.5000"),
                new BigDecimal("5.0000"), new BigDecimal("0.5000"),
                new BigDecimal("5.0000")),

            // 用例10：非整数结果— 3.75×0.65 + 4.2×0.35 = 2.4375+1.47=3.9075
            Arguments.of("用例10-非整数精度",
                new BigDecimal("3.7500"), new BigDecimal("0.6500"),
                new BigDecimal("4.2000"), new BigDecimal("0.3500"),
                new BigDecimal("3.9075"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("finalScoreCases")
    void finalScoreParameterized(String name, BigDecimal projectScore, BigDecimal projectWeight,
                                  BigDecimal funcScore, BigDecimal funcWeight, BigDecimal expected) {
        BigDecimal result = ScoreCalculator.finalScore(projectScore, projectWeight, funcScore, funcWeight);
        assertScoreEquals(expected, result);
    }

    // ========================================
    // 异常边界测试
    // ========================================

    @Test
    void shouldThrowOnNullScores() {
        assertThrows(IllegalArgumentException.class,
                () -> ScoreCalculator.weightedSum(null, List.of(new BigDecimal("1.0"))));
    }

    @Test
    void shouldThrowOnNullWeights() {
        assertThrows(IllegalArgumentException.class,
                () -> ScoreCalculator.weightedSum(List.of(new BigDecimal("1.0")), null));
    }

    @Test
    void shouldThrowOnMismatchedSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> ScoreCalculator.weightedSum(
                        List.of(new BigDecimal("4.0")),
                        List.of(new BigDecimal("0.5"), new BigDecimal("0.5"))));
    }

    @Test
    void shouldReturnZeroForEmptyList() {
        BigDecimal result = ScoreCalculator.weightedSum(List.of(), List.of());
        assertEquals(0, new BigDecimal("0.0000").compareTo(result));
    }

    @Test
    void shouldHandleNullElementInList() {
        // null 元素被跳过，不影响结果（用 Arrays.asList 允许 null 元素）
        List<BigDecimal> scores = Arrays.asList(new BigDecimal("3.0"), null, new BigDecimal("4.0"));
        List<BigDecimal> weights = Arrays.asList(new BigDecimal("0.5000"), new BigDecimal("0.5000"), new BigDecimal("0.5000"));
        BigDecimal result = ScoreCalculator.weightedSum(scores, weights);
        // 3.0×0.5 + 4.0×0.5 = 3.5
        assertScoreEquals(new BigDecimal("3.5000"), result);
    }

    @Test
    void projectCompositeScoreShouldMatchWeightedSum() {
        List<BigDecimal> scores = List.of(new BigDecimal("4.2"), new BigDecimal("3.8"));
        List<BigDecimal> weights = List.of(new BigDecimal("0.6"), new BigDecimal("0.4"));

        BigDecimal viaComposite = ScoreCalculator.projectCompositeScore(scores, weights);
        BigDecimal viaWeighted = ScoreCalculator.weightedSum(scores, weights);
        assertEquals(0, viaComposite.compareTo(viaWeighted));
    }
}
