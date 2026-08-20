// 模块用途：周期监控聚合DTO——单个考核任务的监控行（员工/项目/任务类型/状态/评估人/指标/分数/当前审批人）
// 依赖文件：task.KpiIndicatorDTO.java
// 修改注意：employeeName/projectName/assessorName/indicators/totalScore 由服务层回填，避免前端二次查表
package com.jifeng.assessment.monitor;

import com.jifeng.assessment.task.KpiIndicatorDTO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PeriodMonitorItem {

    private Long taskId;

    /** 被考核人 */
    private String employeeId;
    private String employeeName;

    /** 评估人 */
    private String assessorId;
    private String assessorName;

    /** 项目（FUNCTIONAL 职能任务为 null） */
    private String projectCode;
    private String projectName;
    private String projectStage;

    /** PROJECT / FUNCTIONAL */
    private String taskType;

    /** PENDING / IN_PROGRESS / SUBMITTED / RETURNED / CONFIRMED / CANCELED */
    private String status;

    private Integer returnCount;
    private Integer maxReturns;

    /** KPI 指标列表（含单项得分回填） */
    private List<KpiIndicatorDTO> indicators;

    /** 加权总分 = Σ(score × weight) */
    private BigDecimal totalScore;

    /** 已评分指标数 / 指标总数 */
    private Integer scoredCount;
    private Integer kpiCount;

    /** 当前审批人（PENDING/IN_PROGRESS/RETURNED=评估人；SUBMITTED=PD；终态=null） */
    private String currentApproverId;
    private String currentApproverName;
}
