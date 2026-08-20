// 模块用途：我的考核聚合结果DTO——员工视角的单个项目考核任务及其KPI指标
// 依赖文件：无
// 修改注意：kpis 为评估人角色对应的项目KPI配置（名称/权重/评价标准）
package com.jifeng.assessment.myassessment;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MyAssessmentItem {

    private Long taskId;

    private String taskType;

    private String periodId;

    private String periodName;

    private String projectCode;

    private String projectName;

    private String projectStage;

    private String status;

    private String assessorName;

    private List<KpiItem> kpis;

    @Data
    public static class KpiItem {
        private String kpiName;
        private BigDecimal weight;
        private String evaluationCriteria;
    }
}
