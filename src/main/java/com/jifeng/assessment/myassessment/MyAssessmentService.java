// 模块用途：我的考核业务逻辑——按当前登录员工聚合其 PROJECT/FUNCTIONAL 考核指标(项目/状态/评估人/KPI)
// 依赖文件：TaskMapper.java, ProjectMapper.java, ProjectRoleAssignmentMapper.java, ProjectKpiMapper.java, FuncKpiMapper.java, EmployeeMapper.java, SysUserMapper.java, PeriodMapper.java
// 修改注意：数据源为「角色分配驱动」——员工一旦被分配项目角色即可看到 KPI，不再依赖任务是否已生成；
//   KPI 由被考核人角色 → project_kpi_config / category+position → func_kpi_config 反查；任务仅用于回填状态/评估人
package com.jifeng.assessment.myassessment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MyAssessmentService {

    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final ProjectRoleAssignmentMapper roleAssignmentMapper;
    private final ProjectKpiMapper projectKpiMapper;
    private final FuncKpiMapper funcKpiMapper;
    private final EmployeeMapper employeeMapper;
    private final SysUserMapper sysUserMapper;
    private final PeriodMapper periodMapper;

    private static final String STATUS_NOT_LAUNCHED = "待发起";

    // 功能：聚合当前员工的考核指标——角色分配驱动：
    //   PROJECT：按 project_role_assignment 中已分配的项目角色分组 → project_kpi_config；
    //   FUNCTIONAL：按员工 category+position → func_kpi_config；
    //   任务仅用于回填状态/评估人，无任务时状态=「待发起」、评估人=「-」
    public List<MyAssessmentItem> getMyAssessment() {
        String employeeId = getCurrentEmployeeId();
        if (!StringUtils.hasText(employeeId)) {
            return List.of();
        }

        // 1. 查询该员工的角色分配（deleted=0）——员工视角不再依赖任务是否存在
        List<ProjectRoleAssignment> assignments = roleAssignmentMapper.selectList(
                new LambdaQueryWrapper<ProjectRoleAssignment>()
                        .eq(ProjectRoleAssignment::getEmployeeId, employeeId)
                        .eq(ProjectRoleAssignment::getDeleted, 0));

        // 2. 查询该员工的所有 PROJECT/FUNCTIONAL 任务（仅用于回填状态/评估人/周期）
        List<AssessmentTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AssessmentTask>()
                .eq(AssessmentTask::getAssesseeId, employeeId)
                .in(AssessmentTask::getTaskType, "PROJECT", "FUNCTIONAL"));

        List<MyAssessmentItem> items = new ArrayList<>();

        // 3. PROJECT 指标：按 (projectCode, projectStage) 分组角色分配，TreeMap 保证稳定排序
        Map<String, List<ProjectRoleAssignment>> projectGroups = new TreeMap<>();
        for (ProjectRoleAssignment a : assignments) {
            if (!StringUtils.hasText(a.getProjectCode())) {
                continue;
            }
            String key = a.getProjectCode() + "|" + a.getProjectStage();
            projectGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        for (List<ProjectRoleAssignment> group : projectGroups.values()) {
            String projectCode = group.get(0).getProjectCode();
            String projectStage = group.get(0).getProjectStage();
            List<AssessmentTask> matchingTasks = findTasks(tasks, projectCode, projectStage, "PROJECT");
            items.add(buildProjectItem(projectCode, projectStage, group, matchingTasks));
        }

        // 4. FUNCTIONAL 指标：按员工本人岗位反查（无论是否分配项目角色均展示）
        List<AssessmentTask> funcTasks = findTasks(tasks, null, null, "FUNCTIONAL");
        MyAssessmentItem funcItem = buildFunctionalItem(employeeId, funcTasks);
        if (funcItem != null) {
            items.add(funcItem);
        }

        return items;
    }

    // 功能：组装 PROJECT 指标项——项目名 + 角色 KPI；任务信息通过 backfillTask 回填
    private MyAssessmentItem buildProjectItem(String projectCode, String projectStage,
                                              List<ProjectRoleAssignment> group,
                                              List<AssessmentTask> matchingTasks) {
        MyAssessmentItem item = new MyAssessmentItem();
        item.setTaskType("PROJECT");
        item.setProjectCode(projectCode);
        item.setProjectStage(projectStage);

        Project project = projectMapper.selectByCodeAndStage(projectCode, projectStage);
        item.setProjectName(project != null ? project.getProjectName() : projectCode);

        List<String> roleCodes = group.stream()
                .map(ProjectRoleAssignment::getProjectRoleCode)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        List<ProjectKpiConfig> kpiConfigs = roleCodes.isEmpty() ? List.of() :
                projectKpiMapper.selectList(new LambdaQueryWrapper<ProjectKpiConfig>()
                        .in(ProjectKpiConfig::getProjectRoleCode, roleCodes)
                        .eq(ProjectKpiConfig::getProjectStage, projectStage)
                        .eq(ProjectKpiConfig::getIsActive, true)
                        .orderByAsc(ProjectKpiConfig::getSortOrder));
        item.setKpis(kpiConfigs.stream().map(this::toKpiItem).toList());

        backfillTask(item, matchingTasks);
        return item;
    }

    // 功能：组装 FUNCTIONAL 指标项——按员工 category+position 反查职能 KPI；无配置且无任务时返回 null
    private MyAssessmentItem buildFunctionalItem(String employeeId, List<AssessmentTask> matchingTasks) {
        Employee assessee = employeeMapper.selectById(employeeId);
        if (assessee == null) {
            return null;
        }
        List<FuncKpiConfig> kpiConfigs = funcKpiMapper.selectList(
                new LambdaQueryWrapper<FuncKpiConfig>()
                        .eq(FuncKpiConfig::getCategory, assessee.getCategory())
                        .eq(FuncKpiConfig::getPosition, assessee.getPosition())
                        .eq(FuncKpiConfig::getIsActive, true)
                        .orderByAsc(FuncKpiConfig::getSortOrder));
        // 无职能 KPI 配置时直接从源头过滤，不返回空的职能考核项（即使存在 FUNCTIONAL 任务也不展示空指标）
        if (kpiConfigs.isEmpty()) {
            return null;
        }

        MyAssessmentItem item = new MyAssessmentItem();
        item.setTaskType("FUNCTIONAL");
        item.setProjectName("职能考核");
        item.setKpis(kpiConfigs.stream().map(this::toKpiItem).toList());

        backfillTask(item, matchingTasks);
        return item;
    }

    // 功能：任务信息回填——有任务时取任务状态/周期/评估人，无任务时状态=「待发起」、评估人=「-」
    private void backfillTask(MyAssessmentItem item, List<AssessmentTask> matchingTasks) {
        if (matchingTasks == null || matchingTasks.isEmpty()) {
            item.setTaskId(null);
            item.setStatus(STATUS_NOT_LAUNCHED);
            item.setAssessorName("-");
            return;
        }
        AssessmentTask first = matchingTasks.get(0);
        item.setTaskId(first.getId());
        item.setStatus(first.getStatus());
        item.setPeriodId(first.getPeriodId());
        item.setPeriodName(resolvePeriodName(first.getPeriodId()));
        // 同一项目阶段可能存在多个评估人（多角色），姓名去重后用顿号连接
        item.setAssessorName(matchingTasks.stream()
                .map(AssessmentTask::getAssessorId)
                .filter(StringUtils::hasText)
                .distinct()
                .map(this::resolveName)
                .collect(Collectors.joining("、")));
    }

    // 功能：从任务列表中筛选匹配的任务——projectCode 为 null 表示 FUNCTIONAL 任务
    private List<AssessmentTask> findTasks(List<AssessmentTask> tasks, String projectCode,
                                           String projectStage, String taskType) {
        return tasks.stream()
                .filter(t -> taskType.equals(t.getTaskType()))
                .filter(t -> projectCode == null
                        ? t.getProjectCode() == null
                        : projectCode.equals(t.getProjectCode()) && projectStage.equals(t.getProjectStage()))
                .toList();
    }

    // 功能：项目KPI配置 → 聚合 DTO（名称/权重/评价标准）
    private MyAssessmentItem.KpiItem toKpiItem(ProjectKpiConfig k) {
        MyAssessmentItem.KpiItem kpi = new MyAssessmentItem.KpiItem();
        kpi.setKpiName(k.getKpiName());
        kpi.setWeight(k.getWeight());
        kpi.setEvaluationCriteria(k.getEvaluationCriteria());
        return kpi;
    }

    // 功能：职能KPI配置 → 聚合 DTO（名称/权重/评价标准）
    private MyAssessmentItem.KpiItem toKpiItem(FuncKpiConfig k) {
        MyAssessmentItem.KpiItem kpi = new MyAssessmentItem.KpiItem();
        kpi.setKpiName(k.getKpiName());
        kpi.setWeight(k.getWeight());
        kpi.setEvaluationCriteria(k.getEvaluationCriteria());
        return kpi;
    }

    // 功能：员工工号 → 姓名（未找到则回退工号）
    private String resolveName(String employeeId) {
        Employee e = employeeMapper.selectById(employeeId);
        return e != null ? e.getName() : employeeId;
    }

    // 功能：周期ID → 周期名（未找到则回退 null）
    private String resolvePeriodName(String periodId) {
        if (!StringUtils.hasText(periodId)) {
            return null;
        }
        AssessmentPeriod period = periodMapper.selectById(periodId);
        return period != null ? period.getPeriodName() : null;
    }

    // 功能：根据登录用户名反查员工工号——与 ParticipationService 同源
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
