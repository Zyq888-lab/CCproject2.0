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
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.score.AssessmentScore;
import com.jifeng.assessment.score.ScoreMapper;
import lombok.RequiredArgsConstructor;
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

    // 功能：分页查询考核任务——支持按周期/状态/项目编码/考核人/被考核人筛选
    public PageResult<AssessmentTask> listTasks(PageQuery query, String periodId, String status,
                                                String projectCode, String assessorId, String assesseeId) {
        LambdaQueryWrapper<AssessmentTask> wrapper = new LambdaQueryWrapper<>();
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

        // 查询已有评分（草稿/已提交），按 kpiConfigId 回填 score 和 evidenceUrl
        List<AssessmentScore> existingScores = scoreMapper.selectList(
                new LambdaQueryWrapper<AssessmentScore>()
                        .eq(AssessmentScore::getTaskId, taskId));
        Map<Long, AssessmentScore> scoreMap = existingScores.stream()
                .collect(Collectors.toMap(AssessmentScore::getKpiConfigId, s -> s, (a, b) -> a));

        List<KpiIndicatorDTO> indicators = new ArrayList<>();
        if ("PROJECT".equals(task.getTaskType())) {
            // PROJECT 任务：根据评估人在项目中的角色（project_role_assignment）确定 roleCode，再按 roleCode+stage 查项目 KPI
            List<ProjectRoleAssignment> assignments = roleAssignmentMapper.selectList(
                    new LambdaQueryWrapper<ProjectRoleAssignment>()
                            .eq(ProjectRoleAssignment::getProjectCode, task.getProjectCode())
                            .eq(ProjectRoleAssignment::getProjectStage, task.getProjectStage())
                            .eq(ProjectRoleAssignment::getEmployeeId, task.getAssessorId()));
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
        TaskStatus target = taskStateMachine.transition(
                TaskStatus.valueOf(task.getStatus()), TaskAction.CANCEL,
                task.getReturnCount(), task.getMaxReturns());
        task.setStatus(target.name());
        task.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(task);
        return task;
    }
}
