// 模块用途：结果生成与总裁确认——聚合每人 composite 总分落 assessment_result，确认发布结果
// 依赖文件：ScoreCalculator.java, TaskMapper.java, ScoreMapper.java, ProjectKpiMapper.java, FuncKpiMapper.java,
//   PositionConfigMapper.java, ParticipationMapper.java, EmployeeMapper.java, AssessmentResultMapper.java, PeriodService.java
// 修改注意：composite 是「总分」层级（项目加权 + 职能加权），不落在 assessment_score 指标分行；
//   单组件员工（仅项目/仅职能）权重重归一化 + WARN；结果生成幂等——重复执行按 (assessee, period) upsert
package com.jifeng.assessment.result;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.calibration.AssessmentResult;
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
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
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
    private final ScoreAdjustmentMapper adjustmentMapper;
    private final PeriodMapper periodMapper;
    private final ProjectMapper projectMapper;

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

        // 严格语义：仅聚合「所有非 CANCELED 任务均已 SUBMITTED」的员工；半提交员工（存在任一
        // PENDING/IN_PROGRESS 任务）不生成结果行，归入未提交桶（完整性软门由矩阵/确认页告警，不阻断生成）
        List<AssessmentTask> allTasks = taskMapper.selectList(
                new LambdaQueryWrapper<AssessmentTask>()
                        .eq(AssessmentTask::getPeriodId, periodId)
                        .ne(AssessmentTask::getStatus, "CANCELED"));
        if (allTasks.isEmpty()) {
            return 0;
        }

        // 按被考核人分组任务；半提交员工（组内存在任一非 SUBMITTED 任务）跳空不落库
        Map<String, List<AssessmentTask>> tasksByAssessee = allTasks.stream()
                .collect(Collectors.groupingBy(AssessmentTask::getAssesseeId));

        List<AssessmentTask> submittedTasks = allTasks.stream()
                .filter(t -> STATUS_SUBMITTED.equals(t.getStatus()))
                .toList();
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

        int generated = 0;
        for (Map.Entry<String, List<AssessmentTask>> entry : tasksByAssessee.entrySet()) {
            String assesseeId = entry.getKey();
            List<AssessmentTask> tasks = entry.getValue();

            // 半提交员工：所有非 CANCELED 任务未全部 SUBMITTED，不生成结果行（归入未提交桶）
            boolean fullySubmitted = tasks.stream().allMatch(t -> STATUS_SUBMITTED.equals(t.getStatus()));
            if (!fullySubmitted) {
                continue;
            }

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

    // 功能：查询单个员工的考核结果——结果分=adjusted_score（D3）；KPI 明细来自指标分行（非 composite），
    //   凭证列为 null 时前端回退「凭证暂不可用」；改分原因取最新一条审计行
    public EmployeeResultResponse getEmployeeResult(String periodId, String assesseeId) {
        if (periodMapper.selectById(periodId) == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }
        AssessmentResult result = resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, periodId)
                .eq(AssessmentResult::getAssesseeId, assesseeId));
        if (result == null) {
            throw new BusinessException(404, "该员工在本周期暂无考核结果");
        }

        EmployeeResultResponse resp = new EmployeeResultResponse();
        resp.setPeriodId(periodId);
        AssessmentPeriod period = periodMapper.selectById(periodId);
        resp.setPeriodName(period.getPeriodName());
        resp.setAssesseeId(assesseeId);
        Employee emp = employeeMapper.selectById(assesseeId);
        resp.setEmployeeName(emp != null ? emp.getName() : assesseeId);
        resp.setOriginalScore(result.getOriginalScore());
        resp.setAdjustedScore(result.getAdjustedScore());
        boolean adjusted = result.getOriginalScore() != null && result.getAdjustedScore() != null
                && result.getOriginalScore().compareTo(result.getAdjustedScore()) != 0;
        resp.setAdjusted(adjusted);
        if (adjusted) {
            resp.setDelta(result.getAdjustedScore().subtract(result.getOriginalScore()));
        }

        ScoreAdjustment latest = adjustmentMapper.selectOne(new LambdaQueryWrapper<ScoreAdjustment>()
                .eq(ScoreAdjustment::getAssessmentResultId, result.getId())
                .orderByDesc(ScoreAdjustment::getId)
                .last("LIMIT 1"));
        if (latest != null) {
            resp.setAdjustReason(latest.getReason());
            resp.setAdjustedBy(resolveEmployeeName(latest.getAdjustedBy()));
        }

        // 项目名——取该员工「id 最小」的 SUBMITTED 项目任务反查项目名（纯职能员工为 null）
        AssessmentTask projectTask = taskMapper.selectList(new LambdaQueryWrapper<AssessmentTask>()
                        .eq(AssessmentTask::getPeriodId, periodId)
                        .eq(AssessmentTask::getAssesseeId, assesseeId)
                        .eq(AssessmentTask::getTaskType, "PROJECT")
                        .eq(AssessmentTask::getStatus, STATUS_SUBMITTED)
                        .orderByAsc(AssessmentTask::getId))
                .stream().findFirst().orElse(null);
        if (projectTask != null && projectTask.getProjectCode() != null
                && !projectTask.getProjectCode().isEmpty()) {
            Project project = projectMapper.selectByCodeAndStage(
                    projectTask.getProjectCode(), projectTask.getProjectStage());
            resp.setProjectName(project != null ? project.getProjectName() : projectTask.getProjectCode());
        }

        resp.setKpis(buildKpiDetails(periodId, assesseeId));
        return resp;
    }

    // 功能：构建 KPI 明细——按任务×指标分行展开，回填指标名/权重/评估人/凭证
    private List<EmployeeResultResponse.KpiDetail> buildKpiDetails(String periodId, String assesseeId) {
        List<AssessmentTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AssessmentTask>()
                .eq(AssessmentTask::getPeriodId, periodId)
                .eq(AssessmentTask::getAssesseeId, assesseeId)
                .eq(AssessmentTask::getStatus, STATUS_SUBMITTED)
                .orderByAsc(AssessmentTask::getId));
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<Long> taskIds = tasks.stream().map(AssessmentTask::getId).toList();
        Map<Long, List<AssessmentScore>> scoresByTask = scoreMapper.selectList(
                        new LambdaQueryWrapper<AssessmentScore>()
                                .in(AssessmentScore::getTaskId, taskIds)
                                .eq(AssessmentScore::getStatus, STATUS_SUBMITTED))
                .stream().collect(Collectors.groupingBy(AssessmentScore::getTaskId));

        Map<Long, ProjectKpiConfig> projectKpiById = projectKpiMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProjectKpiConfig::getId, k -> k, (a, b) -> a));
        Map<Long, FuncKpiConfig> funcKpiById = funcKpiMapper.selectList(null).stream()
                .collect(Collectors.toMap(FuncKpiConfig::getId, k -> k, (a, b) -> a));
        Map<String, String> employeeNameById = employeeMapper.selectList(null).stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Employee::getName, (a, b) -> a));

        // 预载项目名（按 code|stage 复合键），供明细行回填所属项目/阶段，避免逐行 N+1
        List<String> projectCodes = tasks.stream()
                .map(AssessmentTask::getProjectCode)
                .filter(code -> code != null && !code.isEmpty())
                .distinct()
                .toList();
        Map<String, String> projectNameByCodeStage = projectCodes.isEmpty() ? Map.of()
                : projectMapper.selectList(new LambdaQueryWrapper<Project>()
                                .in(Project::getProjectCode, projectCodes))
                        .stream().collect(Collectors.toMap(
                                p -> p.getProjectCode() + "|" + p.getProjectStage(),
                                Project::getProjectName, (a, b) -> a));

        List<EmployeeResultResponse.KpiDetail> details = new ArrayList<>();
        for (AssessmentTask task : tasks) {
            String assessorName = employeeNameById.getOrDefault(task.getAssessorId(), task.getAssessorId());
            // 所属项目/阶段：职能任务 projectCode 为 null，三个字段均留空由前端回退展示
            String projectCode = task.getProjectCode();
            String projectStage = task.getProjectStage();
            String projectName = (projectCode == null || projectCode.isEmpty()) ? null
                    : projectNameByCodeStage.getOrDefault(
                            projectCode + "|" + (projectStage == null ? "" : projectStage), projectCode);
            for (AssessmentScore score : scoresByTask.getOrDefault(task.getId(), List.of())) {
                EmployeeResultResponse.KpiDetail d = new EmployeeResultResponse.KpiDetail();
                d.setKpiType(score.getKpiType());
                d.setScore(score.getScore());
                d.setAssessorName(assessorName);
                d.setEvidenceUrl(score.getEvidenceUrl());
                d.setProjectCode(projectCode);
                d.setProjectStage(projectStage);
                d.setProjectName(projectName);
                if ("PROJECT".equals(score.getKpiType())) {
                    ProjectKpiConfig kpi = projectKpiById.get(score.getKpiConfigId());
                    d.setKpiName(kpi != null ? kpi.getKpiName() : null);
                    d.setWeight(kpi != null ? kpi.getWeight() : null);
                } else {
                    FuncKpiConfig kpi = funcKpiById.get(score.getKpiConfigId());
                    d.setKpiName(kpi != null ? kpi.getKpiName() : null);
                    d.setWeight(kpi != null ? kpi.getWeight() : null);
                }
                details.add(d);
            }
        }
        return details;
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
            // 归一化 KPI 权重到和=1（与参与比重归一化口径一致）——避免权重和≠1 导致单任务得分越界
            // （如两个 KPI 各 1.0 求和 2.0 → 归一化后各 0.5；单 KPI 0.7 → 归一化为 1.0，得分不被低估）
            total = total.add(ScoreCalculator.weightedSum(scoreVals, normalizeWeights(weightVals)));
            count++;
        }
        return total.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    // 功能：比重归一化——将权重列表缩放到和为 1（防御不一致数据）；空列表/和为 0 时原样返回（调用方保证非空）
    // 说明：null 权重（如已停用 KPI 无权重）跳过不计入求和，并原样保留 null（weightedSum 会跳过 null 对）
    private List<BigDecimal> normalizeWeights(List<BigDecimal> weights) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal w : weights) {
            if (w != null) {
                sum = sum.add(w);
            }
        }
        if (sum.compareTo(BigDecimal.ZERO) == 0) {
            return weights;
        }
        List<BigDecimal> result = new ArrayList<>(weights.size());
        for (BigDecimal w : weights) {
            result.add(w == null ? null : w.divide(sum, 4, RoundingMode.HALF_UP));
        }
        return result;
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

    // 功能：员工工号 → 姓名（找不到回退原值）——用于校准人 adjustedBy 显示
    private String resolveEmployeeName(String employeeId) {
        if (employeeId == null) {
            return null;
        }
        Employee emp = employeeMapper.selectById(employeeId);
        return emp != null ? emp.getName() : employeeId;
    }
}
