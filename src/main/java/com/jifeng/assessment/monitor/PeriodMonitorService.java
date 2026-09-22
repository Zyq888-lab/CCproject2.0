// 模块用途：周期监控业务逻辑——聚合某周期下所有考核任务，回填员工/项目姓名，按角色控制数据范围
// 依赖文件：PeriodMapper.java, TaskMapper.java, EmployeeMapper.java, ProjectMapper.java, ProjectRoleAssignmentMapper.java, SysUserMapper.java
// 修改注意：ADMIN 全见；PM 仅见自己在 project_role_assignment 中被分配的项目（与 DashboardService 同口径）
package com.jifeng.assessment.monitor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.calibration.CalibrationSubmission;
import com.jifeng.assessment.calibration.CalibrationSubmissionMapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.period.PeriodStatusPolicy;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.score.AssessmentScore;
import com.jifeng.assessment.score.ScoreMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.KpiIndicatorDTO;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PeriodMonitorService {

    private final PeriodMapper periodMapper;
    private final TaskMapper taskMapper;
    private final EmployeeMapper employeeMapper;
    private final ProjectMapper projectMapper;
    private final ProjectRoleAssignmentMapper roleAssignmentMapper;
    private final ProjectKpiMapper projectKpiMapper;
    private final FuncKpiMapper funcKpiMapper;
    private final ScoreMapper scoreMapper;
    private final SysUserMapper sysUserMapper;
    private final CalibrationSubmissionMapper calibrationSubmissionMapper;
    private final ProjectConfirmationMapper projectConfirmationMapper;

    // 功能：聚合某周期的考核任务监控列表——ADMIN 全见，PM 仅见自己项目，回填姓名/项目名
    public List<PeriodMonitorItem> monitor(String periodId) {
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }

        LambdaQueryWrapper<AssessmentTask> wrapper = new LambdaQueryWrapper<AssessmentTask>()
                .eq(AssessmentTask::getPeriodId, periodId);

        // PM 仅见自己项目：项目编码 ∈ PM 被分配的项目集合（职能任务 project_code 为空，不纳入 PM 范围）
        if ("PM".equals(getPrimaryRole())) {
            String employeeId = getCurrentEmployeeId();
            List<String> projectCodes = roleAssignmentMapper.selectList(
                            new LambdaQueryWrapper<ProjectRoleAssignment>()
                                    .eq(ProjectRoleAssignment::getEmployeeId, employeeId))
                    .stream()
                    .map(ProjectRoleAssignment::getProjectCode)
                    .distinct()
                    .toList();
            if (projectCodes.isEmpty()) {
                return List.of();
            }
            wrapper.in(AssessmentTask::getProjectCode, projectCodes);
        }

        wrapper.orderByAsc(AssessmentTask::getId);
        List<AssessmentTask> tasks = taskMapper.selectList(wrapper);
        if (tasks.isEmpty()) {
            return List.of();
        }

        // 批量回填员工/项目姓名，避免 N+1 查表
        Map<String, String> employeeNames = employeeMapper.selectList(null).stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Employee::getName, (a, b) -> a));
        Map<String, String> projectNames = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(
                        p -> p.getProjectCode() + "|" + p.getProjectStage(),
                        Project::getProjectName, (a, b) -> a));

        // 批量回填评分——按 taskId 分组，避免 N+1 查 assessment_score
        List<Long> taskIds = tasks.stream().map(AssessmentTask::getId).toList();
        Map<Long, Map<Long, AssessmentScore>> scoresByTask = scoreMapper.selectList(
                        new LambdaQueryWrapper<AssessmentScore>()
                                .in(AssessmentScore::getTaskId, taskIds))
                .stream()
                .collect(Collectors.groupingBy(AssessmentScore::getTaskId,
                        Collectors.toMap(AssessmentScore::getKpiConfigId, s -> s, (a, b) -> a)));

        // 批量反查 SUBMITTED 项目任务的主 PD 工号——「当前审批人」动态显示所属项目主 PD 姓名，避免 N+1
        Map<String, String> primaryPdByKey = resolvePrimaryPdByProject(tasks);
        // 批量反查 CALIBRATING 已提交项目任务的主总裁工号——「当前审批人」动态显示所属项目主总裁姓名，避免 N+1
        Map<String, String> primaryPresidentByKey = resolvePrimaryPresidentByProject(tasks);

        // 项目级校准提交状态：本周期 calibration_submission（submitted_at 非空）的 (code|stage) 集合，
        //   用于 CALIBRATING 期逐行区分「该项目已提交→总裁待确认 / 未提交→PD待校准」（部分提交时周期级 flag 无法表达）
        String periodStatus = period.getStatus();
        String approvalNode = PeriodStatusPolicy.approvalNode(periodStatus);
        final Set<String> submittedProjectKeys;
        final int submittedProjectCount;
        final int totalProjectCount;
        final Map<String, String> confirmationStatusByKey;
        final int confirmedProjectCount;
        final int confirmationProjectCount;
        if ("CALIBRATION".equals(approvalNode)) {
            submittedProjectKeys = calibrationSubmissionMapper.selectList(
                            new LambdaQueryWrapper<CalibrationSubmission>()
                                    .eq(CalibrationSubmission::getPeriodId, periodId)
                                    .isNotNull(CalibrationSubmission::getSubmittedAt))
                    .stream()
                    .map(s -> projectKey(s.getProjectCode(), s.getProjectStage()))
                    .collect(Collectors.toSet());
            // 本周期涉及项目 = SUBMITTED PROJECT 任务的去重 (code|stage)，与 PeriodService.involvedProjectKeys 同源
            Set<String> involvedKeys = tasks.stream()
                    .filter(t -> "PROJECT".equals(t.getTaskType())
                            && "SUBMITTED".equals(t.getStatus())
                            && t.getProjectCode() != null && !t.getProjectCode().isBlank())
                    .map(t -> projectKey(t.getProjectCode(), t.getProjectStage()))
                    .collect(Collectors.toSet());
            totalProjectCount = involvedKeys.size();
            submittedProjectCount = (int) involvedKeys.stream().filter(submittedProjectKeys::contains).count();

            // 总裁确认状态：批量查本周期 project_confirmation，按 (projectCode, assesseeId) 建索引逐行回填
            List<ProjectConfirmation> confirmations = projectConfirmationMapper.selectList(
                    new LambdaQueryWrapper<ProjectConfirmation>()
                            .eq(ProjectConfirmation::getPeriodId, periodId));
            confirmationStatusByKey = confirmations.stream()
                    .collect(Collectors.toMap(
                            c -> confirmationKey(c.getProjectCode(), c.getAssesseeId()),
                            ProjectConfirmation::getStatus,
                            (a, b) -> a));
            // 顶部「已确认」汇总：project_code 粒度（project_confirmation 无 stage）；
            //   total=distinct project_code，confirmed=该项目所有确认行均 APPROVED 的 project_code
            Map<String, Boolean> allApprovedByProject = confirmations.stream()
                    .collect(Collectors.toMap(
                            ProjectConfirmation::getProjectCode,
                            c -> "APPROVED".equals(c.getStatus()),
                            (a, b) -> a && b));
            confirmationProjectCount = allApprovedByProject.size();
            confirmedProjectCount = (int) allApprovedByProject.values().stream()
                    .filter(Boolean::booleanValue).count();
        } else {
            submittedProjectKeys = Set.of();
            submittedProjectCount = 0;
            totalProjectCount = 0;
            confirmationStatusByKey = Map.of();
            confirmedProjectCount = 0;
            confirmationProjectCount = 0;
        }

        return tasks.stream().map(t -> {
            PeriodMonitorItem item = new PeriodMonitorItem();
            item.setTaskId(t.getId());
            item.setEmployeeId(t.getAssesseeId());
            item.setEmployeeName(employeeNames.get(t.getAssesseeId()));
            item.setAssessorId(t.getAssessorId());
            item.setAssessorName(employeeNames.get(t.getAssessorId()));
            item.setProjectCode(t.getProjectCode());
            item.setProjectStage(t.getProjectStage());
            item.setProjectName(t.getProjectCode() != null
                    ? projectNames.get(t.getProjectCode() + "|" + t.getProjectStage())
                    : null);
            item.setTaskType(t.getTaskType());
            item.setStatus(mapTaskStatusLabel(t.getStatus()));
            item.setNodeLabel(mapTaskNodeLabel(t.getStatus()));

            // 指标 + 分数：反查 KPI 并回填单项得分，计算加权总分与评分进度
            List<KpiIndicatorDTO> indicators = resolveIndicators(t,
                    scoresByTask.getOrDefault(t.getId(), Map.of()));
            item.setIndicators(indicators);
            item.setKpiCount(indicators.size());
            BigDecimal total = BigDecimal.ZERO;
            int scored = 0;
            for (KpiIndicatorDTO kpi : indicators) {
                if (kpi.weight() != null && kpi.score() != null) {
                    total = total.add(kpi.score().multiply(kpi.weight()));
                }
                if (kpi.score() != null) {
                    scored++;
                }
            }
            item.setTotalScore(total);
            item.setScoredCount(scored);

            // 当前审批人：优先周期状态（PeriodStatusPolicy 六态映射）——CALIBRATING 按项目级确认状态分叉：
            //   APPROVED=无（待发布）；RETURNED=主PD待校准；PENDING/无确认行=按校准提交（未提交主PD待校准/已提交主总裁待确认）；
            //   终态(CONFIRMED/PUBLISHED/COMPLETED)=无；否则(INIT/ONGOING)回落任务映射
            if ("CALIBRATION".equals(approvalNode)) {
                boolean calibrationSubmitted = submittedProjectKeys.contains(
                        projectKey(t.getProjectCode(), t.getProjectStage()));
                item.setCalibrationSubmitted(calibrationSubmitted);
                // 项目确认状态：按 (projectCode, assesseeId) 反查 project_confirmation，逐行区分总裁确认进度
                String confirmationStatus = confirmationStatusByKey.get(
                        confirmationKey(t.getProjectCode(), t.getAssesseeId()));
                item.setConfirmationStatus(confirmationStatus);
                // 状态按校准提交状态映射（CALIBRATING 期任务评分状态不再流转，改展示校准维度）：
                //   未提交→待校准，已提交→已校准；总裁已确认/已退回由前端按 confirmationStatus 覆盖
                item.setStatus(calibrationSubmitted ? "已校准" : "待校准");

                if ("APPROVED".equals(confirmationStatus)) {
                    // 总裁已确认：该行无当前审批人（待发布）
                    item.setCurrentApproverId(null);
                    item.setCurrentApproverName(null);
                    item.setNodeLabel("总裁已确认");
                } else if ("RETURNED".equals(confirmationStatus)) {
                    // 总裁退回：回主 PD 重新校准
                    String pdEmployeeId = t.getProjectCode() != null && t.getProjectStage() != null
                            ? primaryPdByKey.get(t.getProjectCode() + "|" + t.getProjectStage())
                            : null;
                    if (pdEmployeeId != null) {
                        item.setCurrentApproverId(pdEmployeeId);
                        item.setCurrentApproverName(employeeNames.get(pdEmployeeId) + "（待校准）");
                    } else {
                        item.setCurrentApproverName("PD（待校准）");
                    }
                    item.setNodeLabel("退回待重提");
                } else if (!calibrationSubmitted) {
                    // 未提交校准（PENDING 或无确认行）：显示所属项目主 PD 姓名（PROJECT 任务）；FUNCTIONAL 无项目或查不到主 PD 时回退占位
                    String pdEmployeeId = t.getProjectCode() != null && t.getProjectStage() != null
                            ? primaryPdByKey.get(t.getProjectCode() + "|" + t.getProjectStage())
                            : null;
                    if (pdEmployeeId != null) {
                        item.setCurrentApproverId(pdEmployeeId);
                        item.setCurrentApproverName(employeeNames.get(pdEmployeeId) + "（待校准）");
                    } else {
                        item.setCurrentApproverName("PD（待校准）");
                    }
                    item.setNodeLabel(item.getCurrentApproverName());
                } else {
                    // 已提交校准且待确认（PENDING 或无确认行）：反查所属项目主总裁姓名（PROJECT 任务）；FUNCTIONAL 无项目或查不到主总裁时回退占位
                    String presidentEmployeeId = t.getProjectCode() != null && t.getProjectStage() != null
                            ? primaryPresidentByKey.get(t.getProjectCode() + "|" + t.getProjectStage())
                            : null;
                    if (presidentEmployeeId != null) {
                        item.setCurrentApproverId(presidentEmployeeId);
                        item.setCurrentApproverName(employeeNames.get(presidentEmployeeId) + "（待确认）");
                    } else {
                        item.setCurrentApproverName("总裁（待确认）");
                    }
                    item.setNodeLabel(item.getCurrentApproverName());
                }
            } else if ("TASK".equals(approvalNode)) {
                String status = t.getStatus();
                if ("PENDING".equals(status) || "IN_PROGRESS".equals(status)) {
                    item.setCurrentApproverId(t.getAssessorId());
                    item.setCurrentApproverName(employeeNames.get(t.getAssessorId()));
                } else if ("SUBMITTED".equals(status)) {
                    // 动态显示所属项目主 PD 姓名；FUNCTIONAL 任务(无项目)或查不到主 PD 时回退占位文案
                    String pdEmployeeId = t.getProjectCode() != null && t.getProjectStage() != null
                            ? primaryPdByKey.get(t.getProjectCode() + "|" + t.getProjectStage())
                            : null;
                    if (pdEmployeeId != null) {
                        item.setCurrentApproverId(pdEmployeeId);
                        item.setCurrentApproverName(employeeNames.get(pdEmployeeId));
                    } else {
                        item.setCurrentApproverName("PD（待确认）");
                    }
                }
            }
            // 周期级信息：监控页据此展示「X/Y 个项目已提交/已确认」提示
            item.setPeriodStatus(periodStatus);
            item.setCalibrationSubmittedAt(period.getCalibrationSubmittedAt());
            item.setSubmittedProjectCount(submittedProjectCount);
            item.setTotalProjectCount(totalProjectCount);
            item.setConfirmedProjectCount(confirmedProjectCount);
            item.setConfirmationProjectCount(confirmationProjectCount);
            return item;
        })
        // 过滤「无职能 KPI 配置」的空职能任务——config 停用/删除后遗留的孤儿 FUNCTIONAL 任务会解析出 0 个指标，
        //   监控看板据此不再展示空 KPI 条目（Issue 2：没有职能 KPI 就不下发/不展示）
        .filter(item -> !("FUNCTIONAL".equals(item.getTaskType())
                && (item.getKpiCount() == null || item.getKpiCount() == 0)))
        .toList();
    }

    // 功能：(projectCode, projectStage) → "code|stage" 复合键——与 PeriodService.taskKey/submissionKey 同口径（空值补空串）
    private String projectKey(String code, String stage) {
        return (code == null ? "" : code) + "|" + (stage == null ? "" : stage);
    }

    // 功能：(projectCode, assesseeId) → "code|assessee" 复合键——与 project_confirmation 唯一键 (period, code, assessee) 同口径
    private String confirmationKey(String code, String assesseeId) {
        return (code == null ? "" : code) + "|" + (assesseeId == null ? "" : assesseeId);
    }

    // 功能：反查任务的 KPI 指标并回填已有评分——与 TaskService.getTaskDetail 同口径（按被考核人角色/岗位）
    private List<KpiIndicatorDTO> resolveIndicators(AssessmentTask task, Map<Long, AssessmentScore> scoreMap) {
        List<KpiIndicatorDTO> indicators = new ArrayList<>();
        if ("PROJECT".equals(task.getTaskType())) {
            // PROJECT 任务：按被考核人(assesseeId)在项目中的角色确定 roleCode，再按 roleCode+stage 查项目 KPI
            String roleEmployeeId = task.getAssesseeId();
            List<ProjectRoleAssignment> assignments = roleAssignmentMapper.selectList(
                    new LambdaQueryWrapper<ProjectRoleAssignment>()
                            .eq(ProjectRoleAssignment::getProjectCode, task.getProjectCode())
                            .eq(ProjectRoleAssignment::getProjectStage, task.getProjectStage())
                            .eq(ProjectRoleAssignment::getEmployeeId, roleEmployeeId));
            List<String> roleCodes = assignments.stream()
                    .map(ProjectRoleAssignment::getProjectRoleCode)
                    .distinct()
                    .toList();
            if (!roleCodes.isEmpty()) {
                List<ProjectKpiConfig> kpis = projectKpiMapper.selectList(
                        new LambdaQueryWrapper<ProjectKpiConfig>()
                                .in(ProjectKpiConfig::getProjectRoleCode, roleCodes)
                                .eq(ProjectKpiConfig::getProjectStage, task.getProjectStage())
                                .eq(ProjectKpiConfig::getIsActive, true)
                                .orderByAsc(ProjectKpiConfig::getSortOrder));
                for (ProjectKpiConfig kpi : kpis) {
                    AssessmentScore s = scoreMap.get(kpi.getId());
                    indicators.add(new KpiIndicatorDTO(
                            kpi.getId(), "PROJECT", kpi.getKpiName(), kpi.getWeight(),
                            kpi.getEvaluationCriteria(),
                            s != null ? s.getScore() : null, s != null ? s.getEvidenceUrl() : null));
                }
            }
        } else if ("FUNCTIONAL".equals(task.getTaskType())) {
            // FUNCTIONAL 任务：根据被考核人的岗位（category+position）查职能 KPI
            Employee assessee = employeeMapper.selectById(task.getAssesseeId());
            if (assessee != null) {
                List<FuncKpiConfig> kpis = funcKpiMapper.selectList(
                        new LambdaQueryWrapper<FuncKpiConfig>()
                                .eq(FuncKpiConfig::getCategory, assessee.getCategory())
                                .eq(FuncKpiConfig::getPosition, assessee.getPosition())
                                .eq(FuncKpiConfig::getIsActive, true)
                                .orderByAsc(FuncKpiConfig::getSortOrder));
                for (FuncKpiConfig kpi : kpis) {
                    AssessmentScore s = scoreMap.get(kpi.getId());
                    indicators.add(new KpiIndicatorDTO(
                            kpi.getId(), "FUNCTIONAL", kpi.getKpiName(), kpi.getWeight(),
                            kpi.getEvaluationCriteria(),
                            s != null ? s.getScore() : null, s != null ? s.getEvidenceUrl() : null));
                }
            }
        }
        return indicators;
    }

    // 功能：任务状态 → 中文状态文案（监控页状态列）：PENDING=待评分 / IN_PROGRESS=评分中 / SUBMITTED=已提交 / CONFIRMED=已确认 / CANCELED=已取消
    private String mapTaskStatusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "待评分";
            case "IN_PROGRESS" -> "评分中";
            case "SUBMITTED" -> "已提交";
            case "CONFIRMED" -> "已确认";
            case "CANCELED" -> "已取消";
            default -> status;
        };
    }

    // 功能：任务状态 → 中文审批节点文案（监控页节点列）：PENDING=待评估人评分 / IN_PROGRESS=评估人评分中 / SUBMITTED=待确认 / CONFIRMED=已完成 / CANCELED=已取消
    private String mapTaskNodeLabel(String status) {
        return switch (status) {
            case "PENDING" -> "待评估人评分";
            case "IN_PROGRESS" -> "评估人评分中";
            case "SUBMITTED" -> "待确认";
            case "CONFIRMED" -> "已完成";
            case "CANCELED" -> "已取消";
            default -> status;
        };
    }

    // 功能：批量反查 SUBMITTED 项目任务所属 (projectCode, projectStage) 的主 PD 工号——
    //   用于「当前审批人」动态显示主 PD 姓名（project_role_code='PD' AND is_primary=true）
    private Map<String, String> resolvePrimaryPdByProject(List<AssessmentTask> tasks) {
        List<String> keys = tasks.stream()
                .filter(t -> "PROJECT".equals(t.getTaskType())
                        && t.getProjectCode() != null && t.getProjectStage() != null)
                .map(t -> t.getProjectCode() + "|" + t.getProjectStage())
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<ProjectRoleAssignment> wrapper = new LambdaQueryWrapper<ProjectRoleAssignment>()
                .eq(ProjectRoleAssignment::getProjectRoleCode, "PD")
                .eq(ProjectRoleAssignment::getIsPrimary, true)
                .eq(ProjectRoleAssignment::getDeleted, 0);
        wrapper.and(w -> {
            boolean first = true;
            for (String key : keys) {
                String[] parts = key.split("\\|", 2);
                String code = parts[0];
                String stage = parts.length > 1 ? parts[1] : "";
                if (first) {
                    w.eq(ProjectRoleAssignment::getProjectCode, code)
                     .eq(ProjectRoleAssignment::getProjectStage, stage);
                    first = false;
                } else {
                    w.or().eq(ProjectRoleAssignment::getProjectCode, code)
                           .eq(ProjectRoleAssignment::getProjectStage, stage);
                }
            }
        });
        return roleAssignmentMapper.selectList(wrapper).stream()
                .filter(a -> a.getEmployeeId() != null && !a.getEmployeeId().isBlank())
                .collect(Collectors.toMap(
                        a -> a.getProjectCode() + "|" + a.getProjectStage(),
                        ProjectRoleAssignment::getEmployeeId,
                        (a, b) -> a)); // 同 (code,stage) 多主 PD 时取第一个
    }

    // 功能：批量反查 CALIBRATING 已提交项目任务所属 (projectCode, projectStage) 的主总裁工号——
    //   用于「当前审批人」动态显示主总裁姓名（project_role_code='PRESIDENT' AND is_primary=true）
    private Map<String, String> resolvePrimaryPresidentByProject(List<AssessmentTask> tasks) {
        List<String> keys = tasks.stream()
                .filter(t -> "PROJECT".equals(t.getTaskType())
                        && t.getProjectCode() != null && t.getProjectStage() != null)
                .map(t -> t.getProjectCode() + "|" + t.getProjectStage())
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<ProjectRoleAssignment> wrapper = new LambdaQueryWrapper<ProjectRoleAssignment>()
                .eq(ProjectRoleAssignment::getProjectRoleCode, "PRESIDENT")
                .eq(ProjectRoleAssignment::getIsPrimary, true)
                .eq(ProjectRoleAssignment::getDeleted, 0);
        wrapper.and(w -> {
            boolean first = true;
            for (String key : keys) {
                String[] parts = key.split("\\|", 2);
                String code = parts[0];
                String stage = parts.length > 1 ? parts[1] : "";
                if (first) {
                    w.eq(ProjectRoleAssignment::getProjectCode, code)
                     .eq(ProjectRoleAssignment::getProjectStage, stage);
                    first = false;
                } else {
                    w.or().eq(ProjectRoleAssignment::getProjectCode, code)
                           .eq(ProjectRoleAssignment::getProjectStage, stage);
                }
            }
        });
        return roleAssignmentMapper.selectList(wrapper).stream()
                .filter(a -> a.getEmployeeId() != null && !a.getEmployeeId().isBlank())
                .collect(Collectors.toMap(
                        a -> a.getProjectCode() + "|" + a.getProjectStage(),
                        ProjectRoleAssignment::getEmployeeId,
                        (a, b) -> a)); // 同 (code,stage) 多主总裁时取第一个
    }

    // 功能：获取当前用户主角色——取权限列表中第一个匹配的已知角色
    private String getPrimaryRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "";
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String a = authority.getAuthority();
            for (String role : new String[]{"ADMIN", "PM", "PD", "评估人", "员工", "总裁"}) {
                if (a.equals("ROLE_" + role)) {
                    return role;
                }
            }
        }
        return "";
    }

    // 功能：从 SecurityContext 用户名反查当前用户 employeeId
    private String getCurrentEmployeeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        return user != null ? user.getEmployeeId() : null;
    }
}
