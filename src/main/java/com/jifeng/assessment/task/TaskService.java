// 模块用途：考核任务业务逻辑——查询任务列表/详情、开始评分、取消任务，状态转换委托状态机
// 依赖文件：TaskMapper.java, AssessmentTask.java, TaskStateMachine.java, BaseService.java, 各 KPI Mapper, ScoreMapper.java
// 修改注意：状态转换必须经过 TaskStateMachine.transition，禁止直接 setStatus
package com.jifeng.assessment.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.score.AssessmentScore;
import com.jifeng.assessment.score.ScoreMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskService extends BaseService<TaskMapper, AssessmentTask> {

    private final TaskStateMachine taskStateMachine;
    private final ProjectKpiMapper projectKpiMapper;
    private final FuncKpiMapper funcKpiMapper;
    private final ProjectRoleAssignmentMapper roleAssignmentMapper;
    private final EmployeeMapper employeeMapper;
    private final ScoreMapper scoreMapper;
    private final SysUserMapper sysUserMapper;
    private final PeriodService periodService;

    // 功能：分页查询考核任务——支持按周期/状态/项目编码/考核人/被考核人筛选；scope=pending/progress 时按当前用户强制隔离
    public PageResult<AssessmentTask> listTasks(PageQuery query, String periodId, String status,
                                                String projectCode, String assessorId, String assesseeId, String scope) {
        LambdaQueryWrapper<AssessmentTask> wrapper = new LambdaQueryWrapper<>();

        // 数据隔离：待评分(scope=pending)强制只看当前用户作为考核人的任务；我的进度(scope=progress)强制只看当前用户作为被考核人的任务；ADMIN 豁免可看全部
        if (!isAdmin()) {
            if ("pending".equals(scope)) {
                assessorId = getCurrentEmployeeId();
                if (assessorId == null) {
                    return PageResult.of(0, query.getPage(), query.getSize(), List.of());
                }
            } else if ("progress".equals(scope)) {
                assesseeId = getCurrentEmployeeId();
                if (assesseeId == null) {
                    return PageResult.of(0, query.getPage(), query.getSize(), List.of());
                }
            } else {
                // 未指定或未知 scope：非 ADMIN 强制只看与自己相关的任务（作为考核人或被考核人），并忽略传入的 assessorId/assesseeId 防越权
                String empId = getCurrentEmployeeId();
                if (empId == null) {
                    return PageResult.of(0, query.getPage(), query.getSize(), List.of());
                }
                assessorId = null;
                assesseeId = null;
                wrapper.and(w -> w.eq(AssessmentTask::getAssessorId, empId)
                        .or().eq(AssessmentTask::getAssesseeId, empId));
            }
        }

        // 自评已移除：所有列表一律排除历史 SELF 残留数据（存量数据清理前的兜底）
        wrapper.ne(AssessmentTask::getTaskType, "SELF");

        // 待评分列表：只显示可评分状态（PENDING/IN_PROGRESS/RETURNED），已提交/已确认/已取消移入「我的进度」
        if ("pending".equals(scope)) {
            wrapper.in(AssessmentTask::getStatus, "PENDING", "IN_PROGRESS", "RETURNED");
            // 待评分列表仅返回已发起(ONGOING)周期的任务，避免 INIT 周期存量任务提前暴露评分入口
            wrapper.inSql(AssessmentTask::getPeriodId,
                    "SELECT period_id FROM assessment_period WHERE status = 'ONGOING' AND deleted = 0");
        }

        if (StringUtils.hasText(periodId)) {
            wrapper.eq(AssessmentTask::getPeriodId, periodId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(AssessmentTask::getStatus, status);
        }
        if (StringUtils.hasText(projectCode)) {
            wrapper.eq(AssessmentTask::getProjectCode, projectCode);
        }
        if (StringUtils.hasText(assessorId)) {
            wrapper.eq(AssessmentTask::getAssessorId, assessorId);
        }
        if (StringUtils.hasText(assesseeId)) {
            wrapper.eq(AssessmentTask::getAssesseeId, assesseeId);
        }
        wrapper.orderByAsc(AssessmentTask::getId);
        return selectPage(query, wrapper);
    }

    // 功能：查询任务详情——返回任务 + 关联的 KPI 指标列表（按 taskType 区分 KPI 来源），并回填已有评分
    public AssessmentTask getTaskDetail(Long taskId) {
        AssessmentTask task = baseMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "考核任务不存在: " + taskId);
        }
        // 权限校验：当前登录用户必须是该任务的考核人（ADMIN 豁免），否则 403
        assertAssessor(task);
        // 周期锁定：考核尚未发起或已关闭时拒绝查看评分页
        periodService.assertOngoing(task.getPeriodId(), "查看评分");

        // 查询已有评分（草稿/已提交），按 kpiConfigId 回填 score 和 evidenceUrl
        List<AssessmentScore> existingScores = scoreMapper.selectList(
                new LambdaQueryWrapper<AssessmentScore>()
                        .eq(AssessmentScore::getTaskId, taskId));
        Map<Long, AssessmentScore> scoreMap = existingScores.stream()
                .collect(Collectors.toMap(AssessmentScore::getKpiConfigId, s -> s, (a, b) -> a));

        List<KpiIndicatorDTO> indicators = new ArrayList<>();
        if (isProjectKpi(task)) {
            // PROJECT 任务：按被考核人(assesseeId)在项目中的角色（project_role_assignment）确定 roleCode，再按 roleCode+stage 查项目 KPI；
            // 原按评估人(assessorId)反查会展示评估人自身角色 KPI 而非被考核人的
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
        } else if (isFunctionalKpi(task)) {
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

        task.setIndicators(indicators);
        return task;
    }

    // 功能：开始评分——PENDING → IN_PROGRESS，经状态机校验
    @Transactional
    public AssessmentTask start(Long taskId) {
        AssessmentTask task = baseMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "考核任务不存在: " + taskId);
        }
        // 权限校验：当前登录用户必须是该任务的考核人（ADMIN 豁免），否则 403
        assertAssessor(task);
        // 周期锁定：考核尚未发起或已关闭时拒绝开始评分
        periodService.assertOngoing(task.getPeriodId(), "开始评分");
        TaskStatus target = taskStateMachine.transition(
                TaskStatus.valueOf(task.getStatus()), TaskAction.START,
                task.getReturnCount(), task.getMaxReturns());
        task.setStatus(target.name());
        task.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(task);
        return task;
    }

    // 功能：取消任务——PENDING/IN_PROGRESS/RETURNED → CANCELED，经状态机校验
    @Transactional
    public AssessmentTask cancel(Long taskId) {
        AssessmentTask task = baseMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "考核任务不存在: " + taskId);
        }
        // 周期锁定：考核周期已关闭时拒绝取消任务
        periodService.assertNotCompleted(task.getPeriodId(), "取消任务");
        TaskStatus target = taskStateMachine.transition(
                TaskStatus.valueOf(task.getStatus()), TaskAction.CANCEL,
                task.getReturnCount(), task.getMaxReturns());
        task.setStatus(target.name());
        task.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(task);
        return task;
    }

    // 功能：打分权限校验——当前登录用户必须是该任务的考核人（ADMIN 豁免），否则 403
    private void assertAssessor(AssessmentTask task) {
        if (isAdmin()) {
            return;
        }
        if (task.getAssessorId() == null || !task.getAssessorId().equals(getCurrentEmployeeId())) {
            throw new BusinessException(403, "无权操作该考核任务");
        }
    }

    // 功能：判断当前登录用户是否 ADMIN——数据隔离/权限校验豁免项
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // 功能：从 Spring Security 上下文取当前用户名，反查员工工号——用于任务数据隔离
    private String getCurrentEmployeeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        return user != null ? user.getEmployeeId() : null;
    }

    // 功能：判断是否为项目KPI来源——PROJECT 任务
    private boolean isProjectKpi(AssessmentTask task) {
        return "PROJECT".equals(task.getTaskType());
    }

    // 功能：判断是否为职能KPI来源——FUNCTIONAL 任务
    private boolean isFunctionalKpi(AssessmentTask task) {
        return "FUNCTIONAL".equals(task.getTaskType());
    }
}
