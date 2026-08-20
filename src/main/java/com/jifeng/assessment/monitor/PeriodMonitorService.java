// 模块用途：周期监控业务逻辑——聚合某周期下所有考核任务，回填员工/项目姓名，按角色控制数据范围
// 依赖文件：PeriodMapper.java, TaskMapper.java, EmployeeMapper.java, ProjectMapper.java, ProjectRoleAssignmentMapper.java, SysUserMapper.java
// 修改注意：ADMIN 全见；PM 仅见自己在 project_role_assignment 中被分配的项目（与 DashboardService 同口径）
package com.jifeng.assessment.monitor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
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
            item.setStatus(t.getStatus());
            item.setReturnCount(t.getReturnCount());
            item.setMaxReturns(t.getMaxReturns());

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

            // 当前审批人：评分阶段=评估人；待确认=PD；终态=无
            String status = t.getStatus();
            if ("PENDING".equals(status) || "IN_PROGRESS".equals(status) || "RETURNED".equals(status)) {
                item.setCurrentApproverId(t.getAssessorId());
                item.setCurrentApproverName(employeeNames.get(t.getAssessorId()));
            } else if ("SUBMITTED".equals(status)) {
                item.setCurrentApproverName("PD（待确认）");
            }
            return item;
        }).toList();
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
                            s != null ? s.getScore() : null, s != null ? s.getEvidenceUrl() : null));
                }
            }
        }
        return indicators;
    }

    // 功能：获取当前用户主角色——取权限列表中第一个匹配的已知角色
    private String getPrimaryRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "";
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String a = authority.getAuthority();
            for (String role : new String[]{"ADMIN", "PM", "PD", "评估人", "员工"}) {
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
