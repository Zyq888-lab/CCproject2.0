// 模块用途：周期监控聚合DTO——单个考核任务的监控行（员工/项目/任务类型/状态/评估人/指标/分数/当前审批人）
// 依赖文件：task.KpiIndicatorDTO.java
// 修改注意：employeeName/projectName/assessorName/indicators/totalScore 由服务层回填，避免前端二次查表
package com.jifeng.assessment.monitor;

import com.jifeng.assessment.task.KpiIndicatorDTO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    /** 状态文案（由任务状态映射：待评分/评分中/已提交/已确认/已取消） */
    private String status;

    /** 当前审批节点文案（由任务状态映射：待评估人评分/评估人评分中/待确认/已完成/已取消） */
    private String nodeLabel;

    /** KPI 指标列表（含单项得分回填） */
    private List<KpiIndicatorDTO> indicators;

    /** 加权总分 = Σ(score × weight) */
    private BigDecimal totalScore;

    /** 已评分指标数 / 指标总数 */
    private Integer scoredCount;
    private Integer kpiCount;

    /** 当前审批人（PENDING/IN_PROGRESS=评估人；SUBMITTED=PD；终态=null） */
    private String currentApproverId;
    private String currentApproverName;

    /** 周期状态（INIT/ONGOING/CALIBRATING/CONFIRMED/PUBLISHED/COMPLETED），监控页据此展示提示 */
    private String periodStatus;

    /** PD 提交校准时间（周期级，NULL=尚未全部提交），监控页据此展示「PD 已提交/尚未提交」 */
    private LocalDateTime calibrationSubmittedAt;

    /** 该项目是否已提交校准（项目级，来自 calibration_submission；CALIBRATING 期逐行渲染审批节点/当前审批人） */
    private Boolean calibrationSubmitted;

    /** 本周期已提交校准的项目数（CALIBRATING 期，周期级，用于顶部汇总提示） */
    private Integer submittedProjectCount;

    /** 本周期涉及的项目总数（CALIBRATING 期，周期级，用于顶部汇总提示） */
    private Integer totalProjectCount;

    /** 该行对应的总裁确认状态（project_confirmation.status：APPROVED/PENDING/RETURNED；无确认行=null），
     *   CALIBRATING 期逐行渲染「总裁已确认/退回待重提/总裁确认中」 */
    private String confirmationStatus;

    /** 本周期已全部确认的项目数（CALIBRATING 期，project_code 粒度，用于顶部「已确认」汇总提示） */
    private Integer confirmedProjectCount;

    /** 本周期需总裁确认的项目总数（CALIBRATING 期，project_code 粒度） */
    private Integer confirmationProjectCount;
}
