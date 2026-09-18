// 模块用途：结果生成与总裁确认——聚合每人 composite 总分落 assessment_result，确认发布结果
// 依赖文件：ScoreCalculator.java, TaskMapper.java, ScoreMapper.java, ProjectKpiMapper.java, FuncKpiMapper.java,
//   PositionConfigMapper.java, ParticipationMapper.java, EmployeeMapper.java, AssessmentResultMapper.java, PeriodService.java
// 修改注意：composite 是「总分」层级（项目加权 + 职能加权），不落在 assessment_score 指标分行；
//   单组件员工（仅项目/仅职能）权重重归一化 + WARN；结果生成幂等——重复执行按 (assessee, period) upsert
package com.jifeng.assessment.result;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.calibration.AssessmentResultMapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.kpi.ScoreCalculator;
import com.jifeng.assessment.participation.EmployeeProjectParticipation;
import com.jifeng.assessment.participation.ParticipationMapper;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigMapper;
import com.jifeng.assessment.score.AssessmentScore;
import com.jifeng.assessment.score.ScoreMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResultService {

    private final TaskMapper taskMapper;
    private final ScoreMapper scoreMapper;
    private final ProjectKpiMapper projectKpiMapper;
    private final FuncKpiMapper funcKpiMapper;
    private final PositionConfigMapper positionConfigMapper;
    private final ParticipationMapper participationMapper;
    private final EmployeeMapper employeeMapper;
    private final AssessmentResultMapper resultMapper;
    private final PeriodMapper periodMapper;

    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final BigDecimal DEFAULT_PROJECT_WEIGHT = new BigDecimal("0.7000");
    private static final BigDecimal DEFAULT_FUNC_WEIGHT = new BigDecimal("0.3000");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // 功能：结果生成——聚合周期内每个已提交(SUBMITTED)员工的项目+职能 composite 总分，upsert 落 assessment_result
    // 幂等：按 (assessee, period) 一行；已手动改分的行不重置 adjusted_score（改分保留原始分）
    @Transactional
    public int generateResults(String periodId) {
        if (periodMapper.selectById(periodId) == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }

        // 仅聚合已提交的任务；未 SUBMITTED 员工跳过不生成结果行（完整性软门由矩阵/确认页告警，不阻断生成）
        List<AssessmentTask> submittedTasks = taskMapper.selectList(
                new LambdaQueryWrapper<AssessmentTask>()
                        .eq(AssessmentTask::getPeriodId, periodId)
                        .eq(AssessmentTask::getStatus, STATUS_SUBMITTED));
        if (submittedTasks.isEmpty()) {
            return 0;
        }

        // 批量预加载：评分按 taskId 分组、KPI 权重按 id 建表、参与记录按员工建表、已有结果按员工建表——避免 N+1
        List<Long> taskIds = submittedTasks.stream().map(AssessmentTask::getId).toList();
        Map<Long, List<AssessmentScore>> scoresByTask = scoreMapper.selectList(
                        new LambdaQueryWrapper<AssessmentScore>()
                                .in(AssessmentScore::getTaskId, taskIds)
                                .eq(AssessmentScore::getStatus, STATUS_SUBMITTED))
                .stream().collect(Collectors.groupingBy(AssessmentScore::getTaskId));

        Map<Long, BigDecimal> projectWeightById = projectKpiMapper.selectList(
                        new LambdaQueryWrapper<ProjectKpiConfig>().eq(ProjectKpiConfig::getIsActive, true))
                .stream().collect(Collectors.toMap(ProjectKpiConfig::getId, ProjectKpiConfig::getWeight, (a, b) -> a));
        Map<Long, BigDecimal> funcWeightById = funcKpiMapper.selectList(
                        new LambdaQueryWrapper<FuncKpiConfig>().eq(FuncKpiConfig::getIsActive, true))
                .stream().collect(Collectors.toMap(FuncKpiConfig::getId, FuncKpiConfig::getWeight, (a, b) -> a));

        Map<String, List<EmployeeProjectParticipation>> participationsByEmployee = participationMapper.selectList(
                        new LambdaQueryWrapper<EmployeeProjectParticipation>()
                                .eq(EmployeeProjectParticipation::getPeriodId, periodId)
                                .eq(EmployeeProjectParticipation::getStatus, "APPROVED"))
                .stream().collect(Collectors.groupingBy(EmployeeProjectParticipation::getEmployeeId));

        // 按被考核人分组任务，逐员工聚合
        Map<String, List<AssessmentTask>> tasksByAssessee = submittedTasks.stream()
                .collect(Collectors.groupingBy(AssessmentTask::getAssesseeId));

        int generated = 0;
        for (Map.Entry<String, List<AssessmentTask>> entry : tasksByAssessee.entrySet()) {
            String assesseeId = entry.getKey();
            List<AssessmentTask> tasks = entry.getValue();

            BigDecimal composite = computeComposite(assesseeId, tasks, scoresByTask,
                    projectWeightById, funcWeightById, participationsByEmployee.get(assesseeId));

            // 原子 upsert：并发生成时 ON CONFLICT 兜底，避免唯一键冲突 500；
            //   已手动改分(original≠adjusted)的行仅刷新 original_score，保留 adjusted_score
            resultMapper.upsertResult(periodId, assesseeId, composite);
            generated++;
        }
        return generated;
    }

    // 功能：统计周期内「未提交」员工数——存在 PENDING/IN_PROGRESS 任务（尚未 SUBMITTED）的去重员工数，
    //   用于校准矩阵/确认页「N 人未提交」告警；已取消(CANCELED)任务不计入
    public int countUnsubmitted(String periodId) {
        if (periodMapper.selectById(periodId) == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }
        return (int) taskMapper.selectList(
                        new LambdaQueryWrapper<AssessmentTask>()
                                .eq(AssessmentTask::getPeriodId, periodId)
                                .in(AssessmentTask::getStatus, "PENDING", "IN_PROGRESS"))
                .stream().map(AssessmentTask::getAssesseeId).distinct().count();
    }

    // 功能：计算单个员工的 composite 总分——项目加权 + 职能加权；
    //   单组件员工（仅项目/仅职能）权重重归一化（单一组件权重置 1）+ WARN 标记
    private BigDecimal computeComposite(String assesseeId, List<AssessmentTask> tasks,
                                        Map<Long, List<AssessmentScore>> scoresByTask,
                                        Map<Long, BigDecimal> projectWeightById,
                                        Map<Long, BigDecimal> funcWeightById,
                                        List<EmployeeProjectParticipation> participations) {
        List<AssessmentTask> projectTasks = tasks.stream()
                .filter(t -> "PROJECT".equals(t.getTaskType())).toList();
        List<AssessmentTask> funcTasks = tasks.stream()
                .filter(t -> "FUNCTIONAL".equals(t.getTaskType())).toList();

        boolean hasProject = !projectTasks.isEmpty();
        boolean hasFunc = !funcTasks.isEmpty();

        BigDecimal projectComposite = hasProject
                ? aggregateProjectComposite(projectTasks, scoresByTask, projectWeightById, participations)
                : null;
        BigDecimal funcComposite = hasFunc
                ? aggregateFuncComposite(funcTasks, scoresByTask, funcWeightById)
                : null;

        // 双组件：按岗位配置权重加权；单组件：权重重归一化（组件权重置 1）+ WARN
        if (hasProject && hasFunc) {
            PositionAssessmentConfig posConfig = resolvePositionConfig(assesseeId);
            BigDecimal projectWeight = posConfig != null ? posConfig.getProjectWeight() : DEFAULT_PROJECT_WEIGHT;
            BigDecimal funcWeight = posConfig != null ? posConfig.getFuncWeight() : DEFAULT_FUNC_WEIGHT;
            if (posConfig == null) {
                log.warn("员工 {} 缺岗位配置，project/func 权重回退默认 {} / {}", assesseeId, projectWeight, funcWeight);
            }
            return ScoreCalculator.finalScore(projectComposite, projectWeight, funcComposite, funcWeight);
        }
        if (hasProject) {
            log.warn("员工 {} 为单组件员工（仅项目），权重重归一化为 1.0", assesseeId);
            return projectComposite;
        }
        log.warn("员工 {} 为单组件员工（仅职能），权重重归一化为 1.0", assesseeId);
        return funcComposite;
    }

    // 功能：聚合项目 composite——按 (项目,阶段) 分组，多评估人取均值，再按参与比重加权（比重归一化为和=1）
    private BigDecimal aggregateProjectComposite(List<AssessmentTask> projectTasks,
                                                 Map<Long, List<AssessmentScore>> scoresByTask,
                                                 Map<Long, BigDecimal> projectWeightById,
                                                 List<EmployeeProjectParticipation> participations) {
        Map<String, List<AssessmentTask>> byProject = new HashMap<>();
        for (AssessmentTask t : projectTasks) {
            String key = key(t.getProjectCode(), t.getProjectStage());
            byProject.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        Map<String, BigDecimal> rateByProject = new HashMap<>();
        if (participations != null) {
            for (EmployeeProjectParticipation p : participations) {
                rateByProject.put(key(p.getProjectCode(), p.getProjectStage()), p.getParticipationRate());
            }
        }

        List<BigDecimal> projectScores = new ArrayList<>();
        List<BigDecimal> rates = new ArrayList<>();
        for (Map.Entry<String, List<AssessmentTask>> e : byProject.entrySet()) {
            BigDecimal projectScore = averageTaskScores(e.getValue(), scoresByTask, projectWeightById);
            projectScores.add(projectScore);
            BigDecimal rate = rateByProject.get(e.getKey());
            rates.add(rate != null ? rate.divide(HUNDRED, 4, RoundingMode.HALF_UP) : BigDecimal.ONE);
        }
        return ScoreCalculator.projectCompositeScore(projectScores, normalizeWeights(rates));
    }

    // 功能：聚合职能 composite——多职能任务取均值（通常仅直属上级一条）
    private BigDecimal aggregateFuncComposite(List<AssessmentTask> funcTasks,
                                              Map<Long, List<AssessmentScore>> scoresByTask,
                                              Map<Long, BigDecimal> funcWeightById) {
        return averageTaskScores(funcTasks, scoresByTask, funcWeightById);
    }

    // 功能：一组任务加权得分的算术均值——单任务即其自身；多任务（多评估人）取平均拉平
    private BigDecimal averageTaskScores(List<AssessmentTask> tasks,
                                         Map<Long, List<AssessmentScore>> scoresByTask,
                                         Map<Long, BigDecimal> weightById) {
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (AssessmentTask task : tasks) {
            List<AssessmentScore> scores = scoresByTask.getOrDefault(task.getId(), List.of());
            List<BigDecimal> scoreVals = scores.stream().map(AssessmentScore::getScore).toList();
            List<BigDecimal> weightVals = scores.stream()
                    .map(s -> weightById.get(s.getKpiConfigId())).toList();
            total = total.add(ScoreCalculator.weightedSum(scoreVals, weightVals));
            count++;
        }
        return total.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    // 功能：比重归一化——将参与比重缩放到和为 1（防御不一致数据）；空列表返回空（调用方保证非空）
    private List<BigDecimal> normalizeWeights(List<BigDecimal> weights) {
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ZERO) == 0) {
            return weights;
        }
        return weights.stream()
                .map(w -> w.divide(sum, 4, RoundingMode.HALF_UP))
                .toList();
    }

    // 功能：按员工岗位（category+position）反查岗位考核配置——与 TaskGeneratorService 同口径
    private PositionAssessmentConfig resolvePositionConfig(String assesseeId) {
        Employee emp = employeeMapper.selectById(assesseeId);
        if (emp == null) {
            return null;
        }
        return positionConfigMapper.selectOne(
                new LambdaQueryWrapper<PositionAssessmentConfig>()
                        .eq(PositionAssessmentConfig::getCategory, emp.getCategory())
                        .eq(PositionAssessmentConfig::getPosition, emp.getPosition())
                        .last("LIMIT 1"));
    }

    // 功能：拼接 (项目,阶段) 分组键——用于项目任务分组与参与比重匹配
    private String key(String projectCode, String projectStage) {
        return (projectCode == null ? "" : projectCode) + "|" + (projectStage == null ? "" : projectStage);
    }
}
