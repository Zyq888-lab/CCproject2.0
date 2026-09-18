// 模块用途：PD 校准业务逻辑——校准矩阵（离群优先）+ 行内改分（乐观锁 + 审计）
// 依赖文件：AssessmentResultMapper.java, ScoreAdjustmentMapper.java, TaskMapper.java,
//   EmployeeMapper.java, ProjectMapper.java, PeriodMapper.java, SysUserMapper.java, ResultService.java
// 修改注意：离群判定基于「原始分」偏离本组（项目/职能）均值 ±1σ；分组规则——有项目任务按
//   首个项目归组，仅职能任务归「职能」组；改分对象是 adjusted_score（composite 总分 0-5），
//   每次改分追加一条 score_adjustment 审计行，old_score 取改分前的 adjusted_score
package com.jifeng.assessment.calibration;

import com.jifeng.assessment.calibration.CalibrationMatrixResponse.GroupSummary;
import com.jifeng.assessment.calibration.CalibrationMatrixResponse.Row;
import com.jifeng.assessment.calibration.CalibrationMatrixResponse.Unsubmitted;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.notification.Notification;
import com.jifeng.assessment.notification.NotificationService;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.result.ResultService;
import com.jifeng.assessment.result.ScoreAdjustment;
import com.jifeng.assessment.result.ScoreAdjustmentMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CalibrationService {

    private final AssessmentResultMapper resultMapper;
    private final ScoreAdjustmentMapper adjustmentMapper;
    private final TaskMapper taskMapper;
    private final EmployeeMapper employeeMapper;
    private final ProjectMapper projectMapper;
    private final PeriodMapper periodMapper;
    private final SysUserMapper sysUserMapper;
    private final ResultService resultService;
    private final NotificationService notificationService;

    private static final String STATUS_CALIBRATING = "CALIBRATING";
    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String FUNC_GROUP_KEY = "functional";
    private static final String FUNC_GROUP_LABEL = "职能考核";
    private static final BigDecimal MAX_SCORE = new BigDecimal("5");

    // 功能：校准矩阵——加载周期内所有已生成的结果行，按「项目/职能」分组算均值与 σ，
    //   标注离群（|偏离度|>1）并按偏离度降序返回（离群优先），附带未提交人数告警
    public CalibrationMatrixResponse getCalibrationMatrix(String periodId) {
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }

        List<AssessmentResult> results = resultMapper.selectList(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getPeriodId, periodId));

        // 批量预加载：员工表 + 项目名 + 已提交任务（供分组）——避免逐行 N+1
        Map<String, String> employeeNameById = employeeMapper.selectList(null).stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Employee::getName, (a, b) -> a));
        Map<String, String> projectNameByCode = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Project::getProjectCode, Project::getProjectName, (a, b) -> a));
        Map<String, List<AssessmentTask>> tasksByAssessee = taskMapper.selectList(
                        new LambdaQueryWrapper<AssessmentTask>()
                                .eq(AssessmentTask::getPeriodId, periodId)
                                .eq(AssessmentTask::getStatus, STATUS_SUBMITTED))
                .stream().collect(Collectors.groupingBy(AssessmentTask::getAssesseeId));

        // 分组聚合原始分：key 为分组键，value 为组内原始分列表
        Map<String, List<BigDecimal>> scoresByGroup = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        for (AssessmentResult result : results) {
            GroupRef ref = resolveGroup(result.getAssesseeId(), tasksByAssessee, projectNameByCode);
            scoresByGroup.computeIfAbsent(ref.key, k -> new ArrayList<>()).add(result.getOriginalScore());

            Row row = new Row();
            row.setAssesseeId(result.getAssesseeId());
            row.setEmployeeName(employeeNameById.getOrDefault(result.getAssesseeId(), result.getAssesseeId()));
            row.setGroupKey(ref.key);
            row.setGroupLabel(ref.label);
            row.setOriginalScore(result.getOriginalScore());
            row.setAdjustedScore(result.getAdjustedScore());
            row.setAdjusted(result.getOriginalScore().compareTo(result.getAdjustedScore()) != 0);
            row.setVersion(result.getVersion());
            rows.add(row);
        }

        // 汇总带：按分组算人数/均值/σ/离群数
        List<GroupSummary> summary = new ArrayList<>();
        for (Map.Entry<String, List<BigDecimal>> e : scoresByGroup.entrySet()) {
            List<BigDecimal> scores = e.getValue();
            BigDecimal avg = mean(scores);
            BigDecimal sigma = sigma(scores, avg);
            int outlierCount = (int) scores.stream()
                    .filter(s -> sigma.compareTo(BigDecimal.ZERO) > 0
                            && s.subtract(avg).abs().compareTo(sigma) > 0)
                    .count();

            GroupSummary gs = new GroupSummary();
            gs.setKey(e.getKey());
            gs.setLabel(groupLabel(e.getKey(), projectNameByCode));
            gs.setCount(scores.size());
            gs.setAvg(avg);
            gs.setSigma(sigma);
            gs.setOutlierCount(outlierCount);
            summary.add(gs);
        }
        summary.sort(Comparator
                .comparingInt(GroupSummary::getCount).reversed()
                .thenComparing(GroupSummary::getLabel));

        // 每行标注偏离度/离群/方向
        Map<String, BigDecimal> avgByGroup = new LinkedHashMap<>();
        Map<String, BigDecimal> sigmaByGroup = new LinkedHashMap<>();
        for (GroupSummary gs : summary) {
            avgByGroup.put(gs.getKey(), gs.getAvg());
            sigmaByGroup.put(gs.getKey(), gs.getSigma());
        }
        for (Row row : rows) {
            BigDecimal avg = avgByGroup.get(row.getGroupKey());
            BigDecimal sigma = sigmaByGroup.get(row.getGroupKey());
            BigDecimal deviation = null;
            boolean outlier = false;
            String direction = null;
            if (sigma != null && sigma.compareTo(BigDecimal.ZERO) > 0) {
                deviation = row.getOriginalScore().subtract(avg)
                        .divide(sigma, 4, RoundingMode.HALF_UP);
                if (deviation.abs().compareTo(BigDecimal.ONE) > 0) {
                    outlier = true;
                    direction = deviation.compareTo(BigDecimal.ZERO) > 0 ? "HIGH" : "LOW";
                }
            }
            row.setDeviation(deviation);
            row.setOutlier(outlier);
            row.setDirection(direction);
        }

        // 离群优先：|偏离度| 降序（σ=0 视作 0 沉底），同幅度按原始分降序、姓名升序
        rows.sort(Comparator
                .comparing((Row r) -> r.getDeviation() == null
                        ? BigDecimal.ZERO : r.getDeviation().abs(), Comparator.reverseOrder())
                .thenComparing(r -> r.getOriginalScore() == null
                        ? BigDecimal.ZERO : r.getOriginalScore(), Comparator.reverseOrder())
                .thenComparing(r -> r.getEmployeeName() == null ? "" : r.getEmployeeName()));

        // 未提交员工列表——与 countUnsubmitted 同口径：PENDING/IN_PROGRESS 任务的去重员工，矩阵底部暗行展示
        List<Unsubmitted> unsubmitted = taskMapper.selectList(new LambdaQueryWrapper<AssessmentTask>()
                        .eq(AssessmentTask::getPeriodId, periodId)
                        .in(AssessmentTask::getStatus, "PENDING", "IN_PROGRESS"))
                .stream()
                .map(AssessmentTask::getAssesseeId)
                .distinct()
                .map(assesseeId -> {
                    Unsubmitted u = new Unsubmitted();
                    u.setAssesseeId(assesseeId);
                    u.setEmployeeName(employeeNameById.getOrDefault(assesseeId, assesseeId));
                    return u;
                })
                .toList();

        CalibrationMatrixResponse resp = new CalibrationMatrixResponse();
        resp.setPeriodId(periodId);
        resp.setPeriodName(period.getPeriodName());
        resp.setUnsubmittedCount(resultService.countUnsubmitted(periodId));
        resp.setUnsubmitted(unsubmitted);
        resp.setSummary(summary);
        resp.setRows(rows);
        return resp;
    }

    // 功能：行内改分——仅 CALIBRATING 周期可改；乐观锁更新 adjusted_score，追加审计行；
    //   old_score 取改分前的 adjusted_score（首次改分即等于 original_score）
    @Transactional
    public void adjust(String periodId, String assesseeId, BigDecimal newScore, String reason) {
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }
        if (!STATUS_CALIBRATING.equals(period.getStatus())) {
            throw new BusinessException(400, "仅校准中的周期可改分");
        }
        if (newScore == null || newScore.compareTo(BigDecimal.ZERO) < 0 || newScore.compareTo(MAX_SCORE) > 0) {
            throw new BusinessException(400, "调整后分数需在 0-5 之间");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(400, "请选择改分原因");
        }

        AssessmentResult result = resultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getPeriodId, periodId)
                        .eq(AssessmentResult::getAssesseeId, assesseeId));
        if (result == null) {
            throw new BusinessException(404, "该员工无结果记录: " + assesseeId);
        }

        BigDecimal normalized = newScore.setScale(4, RoundingMode.HALF_UP);
        BigDecimal oldScore = result.getAdjustedScore();
        result.setAdjustedScore(normalized);
        result.setUpdatedAt(LocalDateTime.now());
        int updated = resultMapper.updateById(result); // @Version 乐观锁：版本过期返回 0
        if (updated == 0) {
            throw new BusinessException(409, "该行已被他人修改，请刷新后重试");
        }

        ScoreAdjustment audit = new ScoreAdjustment();
        audit.setAssessmentResultId(result.getId());
        audit.setAdjustedBy(currentOperatorId());
        audit.setOldScore(oldScore);
        audit.setNewScore(normalized);
        audit.setReason(reason);
        audit.setCreatedAt(LocalDateTime.now());
        adjustmentMapper.insert(audit);

        // 改分成功 → 通知评估人（best-effort 异步，失败不影响改分主流程）
        Employee assessee = employeeMapper.selectById(assesseeId);
        notifyAssessorsAfterAdjust(periodId, assesseeId,
                assessee != null ? assessee.getName() : assesseeId, oldScore, normalized, reason);
    }

    // 功能：改分后通知评估人——将被考核人 SUBMITTED 任务的评估人(employeeId)映射为 userId，
    //   发送含「原分→新分 + 差额 + 原因」的站内通知；通知失败仅记录日志不影响改分
    private void notifyAssessorsAfterAdjust(String periodId, String assesseeId, String employeeName,
                                            BigDecimal oldScore, BigDecimal newScore, String reason) {
        Set<String> assessorIds = taskMapper.selectList(new LambdaQueryWrapper<AssessmentTask>()
                        .eq(AssessmentTask::getPeriodId, periodId)
                        .eq(AssessmentTask::getAssesseeId, assesseeId)
                        .eq(AssessmentTask::getStatus, STATUS_SUBMITTED))
                .stream()
                .map(AssessmentTask::getAssessorId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (assessorIds.isEmpty()) {
            return;
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getEmployeeId, assessorIds));
        if (users.isEmpty()) {
            return;
        }

        BigDecimal delta = newScore.subtract(oldScore);
        String deltaText = (delta.signum() >= 0 ? "+" : "") + delta.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String content = "您评估的员工「" + employeeName + "」总分已由 "
                + oldScore.setScale(2, RoundingMode.HALF_UP).toPlainString() + " 调整为 "
                + newScore.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "（差额 " + deltaText + "），原因：" + reason;
        List<Notification> notifications = users.stream().map(user -> {
            Notification n = new Notification();
            n.setRecipientId(user.getUserId());
            n.setTitle("考核结果改分通知");
            n.setContent(content);
            n.setType("SCORE_ADJUSTED");
            n.setTargetUrl("/tasks");
            n.setIsRead(false);
            return n;
        }).toList();
        notificationService.notifyBatch(notifications);
    }

    // 功能：解析员工分组——有 SUBMITTED 项目任务归「首个项目」组，仅职能任务归「职能」组
    private GroupRef resolveGroup(String assesseeId, Map<String, List<AssessmentTask>> tasksByAssessee,
                                  Map<String, String> projectNameByCode) {
        List<AssessmentTask> tasks = tasksByAssessee.getOrDefault(assesseeId, List.of());
        AssessmentTask projectTask = tasks.stream()
                .filter(t -> "PROJECT".equals(t.getTaskType()))
                .min(Comparator.comparing(AssessmentTask::getId))
                .orElse(null);
        if (projectTask != null) {
            String code = projectTask.getProjectCode();
            String name = projectNameByCode.getOrDefault(code, code);
            String label = StringUtils.hasText(projectTask.getProjectStage())
                    ? name + "·" + projectTask.getProjectStage()
                    : name;
            return new GroupRef("project:" + code, label);
        }
        return new GroupRef(FUNC_GROUP_KEY, FUNC_GROUP_LABEL);
    }

    // 功能：汇总带展示名——项目分组从键反查项目名，职能组用固定文案（兜底防键缺失）
    private String groupLabel(String key, Map<String, String> projectNameByCode) {
        if (FUNC_GROUP_KEY.equals(key)) {
            return FUNC_GROUP_LABEL;
        }
        String code = key.startsWith("project:") ? key.substring("project:".length()) : key;
        return projectNameByCode.getOrDefault(code, code);
    }

    // 功能：当前操作用户工号——反查 sys_user 取 employee_id，无则回退用户名，再回退 "system"（审计列 NOT NULL）
    private String currentOperatorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "system";
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        if (user != null && StringUtils.hasText(user.getEmployeeId())) {
            return user.getEmployeeId();
        }
        return auth.getName();
    }

    // 功能：算术均值（0-5 分制，4 位小数）
    private BigDecimal mean(List<BigDecimal> xs) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal x : xs) {
            sum = sum.add(x);
        }
        return sum.divide(BigDecimal.valueOf(xs.size()), 4, RoundingMode.HALF_UP);
    }

    // 功能：总体标准差 σ = sqrt(Σ(x-μ)²/n)；n=1 时 σ=0（无法判定离群）
    private BigDecimal sigma(List<BigDecimal> xs, BigDecimal mean) {
        if (xs.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal ss = BigDecimal.ZERO;
        for (BigDecimal x : xs) {
            BigDecimal d = x.subtract(mean);
            ss = ss.add(d.multiply(d));
        }
        BigDecimal variance = ss.divide(BigDecimal.valueOf(xs.size()), 8, RoundingMode.HALF_UP);
        return variance.sqrt(new MathContext(8, RoundingMode.HALF_UP)).setScale(4, RoundingMode.HALF_UP);
    }

    // 分组引用——键 + 展示名（内部临时结构）
    private record GroupRef(String key, String label) {}
}
