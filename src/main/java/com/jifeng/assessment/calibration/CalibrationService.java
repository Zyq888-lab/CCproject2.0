// 模块用途：PD 校准业务逻辑——校准矩阵（离群优先）+ 行内改分（乐观锁 + 审计）
// 依赖文件：AssessmentResultMapper.java, ScoreAdjustmentMapper.java, TaskMapper.java,
//   EmployeeMapper.java, ProjectMapper.java, PeriodMapper.java, SysUserMapper.java, ResultService.java
// 修改注意：离群判定基于「原始分」偏离本组（项目/职能）均值 ±1σ；分组规则——有项目任务按
//   (projectCode, projectStage) 逐组展开（多项目/多阶段员工分别出现在各自组），仅职能任务归「职能」组；
//   改分对象是 adjusted_score（composite 总分 0-5），每次改分追加一条 score_adjustment 审计行，
//   old_score 取改分前的 adjusted_score
package com.jifeng.assessment.calibration;

import com.jifeng.assessment.calibration.CalibrationMatrixResponse.GroupSummary;
import com.jifeng.assessment.calibration.CalibrationMatrixResponse.Row;
import com.jifeng.assessment.calibration.CalibrationMatrixResponse.Unsubmitted;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.notification.Notification;
import com.jifeng.assessment.notification.NotificationService;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.kpi.ScoreCalculator;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.result.ResultService;
import com.jifeng.assessment.result.ScoreAdjustment;
import com.jifeng.assessment.result.ScoreAdjustmentMapper;
import com.jifeng.assessment.result.ScoreKpiAdjustment;
import com.jifeng.assessment.result.ScoreKpiAdjustmentMapper;
import com.jifeng.assessment.score.AssessmentScore;
import com.jifeng.assessment.score.ScoreMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.KpiIndicatorDTO;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.task.TaskService;
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
    private final ProjectRoleAssignmentMapper roleAssignmentMapper;
    private final ProjectConfirmationMapper projectConfirmationMapper;
    private final CalibrationSubmissionMapper calibrationSubmissionMapper;
    private final TaskService taskService;
    private final ScoreMapper scoreMapper;
    private final ScoreKpiAdjustmentMapper kpiAdjustmentMapper;
    private final AssessmentProjectSubtotalMapper projectSubtotalMapper;

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
        // 项目名按 (projectCode|projectStage) 复合键建映射——project 表复合主键 (code, stage)，
        //   同 code 不同 stage 可有不同 project_name，按单 code 取会压扁到任意一行（问题1）
        Map<String, String> projectNameByCodeStage = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(
                        p -> p.getProjectCode() + "|" + p.getProjectStage(),
                        Project::getProjectName, (a, b) -> a));
        Map<String, List<AssessmentTask>> tasksByAssessee = taskMapper.selectList(
                        new LambdaQueryWrapper<AssessmentTask>()
                                .eq(AssessmentTask::getPeriodId, periodId)
                                .eq(AssessmentTask::getStatus, STATUS_SUBMITTED))
                .stream().collect(Collectors.groupingBy(AssessmentTask::getAssesseeId));

        // 批量预加载已提交任务的评分行——按 taskId 建索引，供每行重算项目任务小计（避免逐行 N+1）
        List<Long> submittedTaskIds = tasksByAssessee.values().stream()
                .flatMap(List::stream).map(AssessmentTask::getId).toList();
        Map<Long, List<AssessmentScore>> scoresByTask = submittedTaskIds.isEmpty()
                ? Map.of()
                : scoreMapper.selectList(new LambdaQueryWrapper<AssessmentScore>()
                        .in(AssessmentScore::getTaskId, submittedTaskIds))
                .stream().collect(Collectors.groupingBy(AssessmentScore::getTaskId));

        // 项目负责人/总裁只读视角：仅返回自己作为主角色（is_primary=true）负责的项目的评分明细；
        //   总裁 → PRESIDENT、PD → PD；ADMIN 及其它角色无此过滤，ownedProjectKeys 保持 null；键形如 code|stage
        Set<String> ownedProjectKeys = ownedProjectKeys();
        if (ownedProjectKeys != null) {
            results = results.stream()
                    .filter(r -> inOwnedProjects(r.getAssesseeId(), tasksByAssessee, ownedProjectKeys))
                    .toList();
        }

        // 批量预加载项目确认行——按 (projectCode, assesseeId) 建索引，回填每人的总裁确认状态与退回意见（问题2）
        Map<String, ProjectConfirmation> confirmationByKey = projectConfirmationMapper.selectList(
                        new LambdaQueryWrapper<ProjectConfirmation>()
                                .eq(ProjectConfirmation::getPeriodId, periodId))
                .stream()
                .collect(Collectors.toMap(
                        c -> c.getProjectCode() + "::" + c.getAssesseeId(),
                        c -> c,
                        (a, b) -> a));

        // 分组聚合原始分：key 为分组键，value 为组内原始分列表；
        //   多项目/多阶段员工按 (code|stage) 逐组展开——同一员工各成一行、分别计入各自组的均值/σ
        Map<String, List<BigDecimal>> scoresByGroup = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        for (AssessmentResult result : results) {
            for (GroupRef ref : resolveGroups(result.getAssesseeId(), tasksByAssessee, projectNameByCodeStage, ownedProjectKeys)) {
                // 每行按各自项目任务重算小计（Σ score×归一化weight），不再取周期级 composite——
                //   消除多项目员工各项目行共享同一 composite 的问题
                GroupTotals totals = computeGroupTotals(ref.tasks, scoresByTask, employeeNameById);
                scoresByGroup.computeIfAbsent(ref.key, k -> new ArrayList<>()).add(totals.originalSubtotal());

                Row row = new Row();
                row.setAssesseeId(result.getAssesseeId());
                row.setEmployeeName(employeeNameById.getOrDefault(result.getAssesseeId(), result.getAssesseeId()));
                row.setGroupKey(ref.key);
                row.setGroupLabel(ref.label);
                row.setOriginalScore(totals.originalSubtotal());
                row.setAdjustedScore(totals.adjustedSubtotal());
                row.setAdjusted(totals.originalSubtotal().compareTo(totals.adjustedSubtotal()) != 0);
                row.setTaskId(ref.tasks.isEmpty() ? null : ref.tasks.get(0).getId());
                row.setKpis(totals.kpis());
                row.setVersion(result.getVersion());

                // 回填总裁确认状态与退回意见——项目型员工按 (projectCode, assesseeId) 反查确认行；
                //   group key 形如 project:<code>|<stage>，先反解出 code 再匹配确认行
                String rowProjectCode = projectCodeFromKey(ref.key);
                if (rowProjectCode != null) {
                    ProjectConfirmation confirmation = confirmationByKey.get(rowProjectCode + "::" + result.getAssesseeId());
                    if (confirmation != null) {
                        row.setConfirmationStatus(confirmation.getStatus());
                        row.setReturnReason(confirmation.getReturnReason());
                    }
                }

                rows.add(row);
            }
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
            gs.setLabel(groupLabel(e.getKey(), projectNameByCodeStage));
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
                .filter(t -> ownedProjectKeys == null
                        || (t.getProjectCode() != null && ownedProjectKeys.contains(taskKey(t))))
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
        resp.setCalibrationSubmittedAt(period.getCalibrationSubmittedAt());
        // 项目级提交粒度：PD 只关注自己名下项目是否全部提交/是否被退回；ADMIN 及其它角色沿用周期级口径
        resp.setSubmitted(ownedProjectKeys == null
                ? period.getCalibrationSubmittedAt() != null
                : ownedProjectsAllSubmitted(periodId, ownedProjectKeys));
        resp.setHasReturned(ownedProjectKeys == null
                ? hasReturnedConfirmations(periodId)
                : hasReturnedConfirmationsForOwned(periodId, ownedProjectKeys));
        resp.setUnsubmittedCount(ownedProjectKeys != null
                ? unsubmitted.size() : resultService.countUnsubmitted(periodId));
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

    // 功能：解析员工分组——按 SUBMITTED 项目任务的 (projectCode, projectStage) 逐组展开：
    //   员工在多个项目/阶段有任务时分别出现在各自组，不再取最小 id 单一归组；仅职能任务归「职能」组。
    //   分组键与项目名均按 (projectCode, projectStage) 复合口径，同 code 不同 stage 分属不同组。
    //   总裁/PD 视角（ownedProjectKeys 非空）下，只展开落在自己负责的 (code|stage) 集合内的项目任务，
    //   避免员工因另属他人项目而出现在非负责项目组，导致看到非负责项目的名字
    private List<GroupRef> resolveGroups(String assesseeId, Map<String, List<AssessmentTask>> tasksByAssessee,
                                         Map<String, String> projectNameByCodeStage,
                                         Set<String> ownedProjectKeys) {
        List<AssessmentTask> all = tasksByAssessee.getOrDefault(assesseeId, List.of());
        List<AssessmentTask> projectTasks = all.stream()
                .filter(t -> "PROJECT".equals(t.getTaskType()))
                .toList();
        if (projectTasks.isEmpty()) {
            // 仅职能员工：归「职能」组，绑定全部职能任务（多个评估人取均值）
            List<AssessmentTask> funcTasks = all.stream()
                    .filter(t -> "FUNCTIONAL".equals(t.getTaskType()))
                    .toList();
            return List.of(new GroupRef(FUNC_GROUP_KEY, FUNC_GROUP_LABEL, funcTasks));
        }
        // 逐组展开：按 (code|stage) 去重，各成一组；有 owned 过滤时只保留自己负责的项目
        List<GroupRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AssessmentTask task : projectTasks) {
            String key = taskKey(task);
            if (ownedProjectKeys != null && !ownedProjectKeys.contains(key)) {
                continue;
            }
            if (!seen.add(key)) {
                continue;
            }
            String code = task.getProjectCode();
            String stage = task.getProjectStage();
            String name = projectNameByCodeStage.getOrDefault(code + "|" + stage, code);
            String label = StringUtils.hasText(stage) ? name + "·" + stage : name;
            refs.add(new GroupRef("project:" + key, label, List.of(task)));
        }
        // 若员工所有项目任务都被 owned 过滤掉，则不落入任何组（该员工已在结果行过滤阶段被排除）
        return refs;
    }

    // 功能：汇总带展示名——项目分组从键反查项目名并带出阶段，职能组用固定文案（兜底防键缺失）
    private String groupLabel(String key, Map<String, String> projectNameByCodeStage) {
        if (FUNC_GROUP_KEY.equals(key)) {
            return FUNC_GROUP_LABEL;
        }
        String composite = key.startsWith("project:") ? key.substring("project:".length()) : key;
        int sep = composite.indexOf('|');
        String code = sep >= 0 ? composite.substring(0, sep) : composite;
        String stage = sep >= 0 ? composite.substring(sep + 1) : "";
        String name = projectNameByCodeStage.getOrDefault(composite, code);
        return StringUtils.hasText(stage) ? name + "·" + stage : name;
    }

    // 功能：从项目分组键反解 projectCode——键形如 project:<code>|<stage>
    private String projectCodeFromKey(String key) {
        if (key == null || !key.startsWith("project:")) {
            return null;
        }
        String composite = key.substring("project:".length());
        int sep = composite.indexOf('|');
        return sep >= 0 ? composite.substring(0, sep) : composite;
    }

    // 功能：任务 → (code|stage) 复合键——与分组键、总裁负责项目键同口径
    private String taskKey(AssessmentTask task) {
        return (task.getProjectCode() == null ? "" : task.getProjectCode())
                + "|" + (task.getProjectStage() == null ? "" : task.getProjectStage());
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

    // 功能：判断当前用户是否具有指定角色（如 ADMIN/总裁）——检查权限列表中是否含 ROLE_<role>
    private boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    // 功能：查询当前用户作为主角色（is_primary=true）负责的项目 (code|stage) 复合键集合；
    //   总裁 → PRESIDENT 角色、PD → PD 角色；ADMIN 及其它角色返回 null（不过滤）；未登录/未绑定返回空集
    private Set<String> ownedProjectKeys() {
        if (hasRole("ADMIN")) {
            return null;
        }
        String roleCode = hasRole("总裁") ? "PRESIDENT" : (hasRole("PD") ? "PD" : null);
        if (roleCode == null) {
            return null;
        }
        String employeeId = currentEmployeeId();
        if (employeeId == null) {
            return Set.of();
        }
        return roleAssignmentMapper.selectList(new LambdaQueryWrapper<ProjectRoleAssignment>()
                        .eq(ProjectRoleAssignment::getEmployeeId, employeeId)
                        .eq(ProjectRoleAssignment::getProjectRoleCode, roleCode)
                        .eq(ProjectRoleAssignment::getIsPrimary, true)
                        .eq(ProjectRoleAssignment::getDeleted, 0))
                .stream()
                .map(a -> (a.getProjectCode() == null ? "" : a.getProjectCode())
                        + "|" + (a.getProjectStage() == null ? "" : a.getProjectStage()))
                .collect(Collectors.toSet());
    }

    // 功能：从 SecurityContext 用户名反查当前用户 employeeId，未登录/未绑定返回 null
    private String currentEmployeeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        return user != null ? user.getEmployeeId() : null;
    }

    // 功能：判断员工是否有 SUBMITTED 项目任务落在当前用户负责的 (code|stage) 集合内（总裁/PD 只读视角过滤用）
    private boolean inOwnedProjects(String assesseeId, Map<String, List<AssessmentTask>> tasksByAssessee,
                                    Set<String> ownedProjectKeys) {
        return tasksByAssessee.getOrDefault(assesseeId, List.of()).stream()
                .filter(t -> "PROJECT".equals(t.getTaskType()))
                .map(this::taskKey)
                .anyMatch(ownedProjectKeys::contains);
    }

    // 功能：本周期是否存在 RETURNED 确认行（周期级口径，ADMIN 及其它角色用）——总裁退回后前端据此放开「提交校准」按钮（问题2）
    private boolean hasReturnedConfirmations(String periodId) {
        Long count = projectConfirmationMapper.selectCount(
                new LambdaQueryWrapper<ProjectConfirmation>()
                        .eq(ProjectConfirmation::getPeriodId, periodId)
                        .eq(ProjectConfirmation::getStatus, "RETURNED"));
        return count != null && count > 0;
    }

    // 功能：本周期涉及的 (code|stage) 项目键——来自 SUBMITTED PROJECT 任务，与校准矩阵分组同源
    private Set<String> involvedProjectKeys(String periodId) {
        return taskMapper.selectList(new LambdaQueryWrapper<AssessmentTask>()
                        .eq(AssessmentTask::getPeriodId, periodId)
                        .eq(AssessmentTask::getTaskType, "PROJECT")
                        .eq(AssessmentTask::getStatus, STATUS_SUBMITTED)
                        .isNotNull(AssessmentTask::getProjectCode)
                        .ne(AssessmentTask::getProjectCode, ""))
                .stream()
                .map(this::taskKey)
                .collect(Collectors.toSet());
    }

    // 功能：当前 PD 名下本周期涉及的项目是否全部已提交（项目级提交粒度，前端提交按钮据此禁用/放开）
    private boolean ownedProjectsAllSubmitted(String periodId, Set<String> ownedProjectKeys) {
        Set<String> mine = involvedProjectKeys(periodId).stream()
                .filter(ownedProjectKeys::contains)
                .collect(Collectors.toSet());
        if (mine.isEmpty()) {
            return false; // 无负责项目 → 不可提交
        }
        Set<String> submitted = calibrationSubmissionMapper.selectList(
                        new LambdaQueryWrapper<CalibrationSubmission>()
                                .eq(CalibrationSubmission::getPeriodId, periodId)
                                .isNotNull(CalibrationSubmission::getSubmittedAt))
                .stream()
                .map(s -> submissionKey(s))
                .collect(Collectors.toSet());
        return submitted.containsAll(mine);
    }

    // 功能：当前 PD 名下项目是否存在 RETURNED 确认行——总裁退回后仅被退回 PD 需重新提交（项目级口径）
    private boolean hasReturnedConfirmationsForOwned(String periodId, Set<String> ownedProjectKeys) {
        List<String> codes = ownedProjectKeys.stream()
                .map(this::codeFromCompositeKey)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (codes.isEmpty()) {
            return false;
        }
        Long count = projectConfirmationMapper.selectCount(
                new LambdaQueryWrapper<ProjectConfirmation>()
                        .eq(ProjectConfirmation::getPeriodId, periodId)
                        .in(ProjectConfirmation::getProjectCode, codes)
                        .eq(ProjectConfirmation::getStatus, "RETURNED"));
        return count != null && count > 0;
    }

    // 功能：从 (code|stage) 复合键反解 projectCode
    private String codeFromCompositeKey(String key) {
        if (key == null) {
            return null;
        }
        int sep = key.indexOf('|');
        return sep >= 0 ? key.substring(0, sep) : key;
    }

    // 功能：校准提交记录 → (code|stage) 复合键——与分组键/涉及项目键同口径
    private String submissionKey(CalibrationSubmission s) {
        return (s.getProjectCode() == null ? "" : s.getProjectCode())
                + "|" + (s.getProjectStage() == null ? "" : s.getProjectStage());
    }

    // 功能：组小计——项目组取单任务小计；职能组多任务取均值（与 ResultService.averageTaskScores 同口径）
    private GroupTotals computeGroupTotals(List<AssessmentTask> tasks,
                                           Map<Long, List<AssessmentScore>> scoresByTask,
                                           Map<String, String> employeeNameById) {
        if (tasks.isEmpty()) {
            BigDecimal zero = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            return new GroupTotals(zero, zero, List.of());
        }
        BigDecimal origSum = BigDecimal.ZERO;
        BigDecimal adjSum = BigDecimal.ZERO;
        List<CalibrationMatrixResponse.Kpi> kpis = new ArrayList<>();
        for (AssessmentTask task : tasks) {
            TaskTotals t = computeTaskTotals(task, scoresByTask, employeeNameById);
            origSum = origSum.add(t.originalSubtotal());
            adjSum = adjSum.add(t.adjustedSubtotal());
            if (kpis.isEmpty()) {
                kpis.addAll(t.kpis());
            }
        }
        int n = tasks.size();
        BigDecimal originalSubtotal = origSum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        BigDecimal adjustedSubtotal = adjSum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        return new GroupTotals(originalSubtotal, adjustedSubtotal, kpis);
    }

    // 功能：单任务小计——resolveIndicators 取 KPI 权重与名称，assessment_score 取原始分/覆盖分，
    //   用 ScoreCalculator.weightedSum(score, 归一化weight) 计算，口径与 ResultService.averageTaskScores 一致
    private TaskTotals computeTaskTotals(AssessmentTask task,
                                         Map<Long, List<AssessmentScore>> scoresByTask,
                                         Map<String, String> employeeNameById) {
        List<KpiIndicatorDTO> indicators = taskService.resolveIndicators(task);
        Map<Long, AssessmentScore> scoreByKpi = scoresByTask.getOrDefault(task.getId(), List.of()).stream()
                .collect(Collectors.toMap(AssessmentScore::getKpiConfigId, s -> s, (a, b) -> a));
        String assessorName = task.getAssessorId() == null ? null
                : (employeeNameById == null ? task.getAssessorId()
                   : employeeNameById.getOrDefault(task.getAssessorId(), task.getAssessorId()));

        List<BigDecimal> originalVals = new ArrayList<>();
        List<BigDecimal> effectiveVals = new ArrayList<>();
        List<BigDecimal> weightVals = new ArrayList<>();
        List<CalibrationMatrixResponse.Kpi> kpis = new ArrayList<>();
        for (KpiIndicatorDTO dto : indicators) {
            AssessmentScore s = scoreByKpi.get(dto.kpiConfigId());
            BigDecimal original = s != null ? s.getScore() : null;
            BigDecimal calibrated = s != null ? s.getCalibratedScore() : null;
            BigDecimal effective = calibrated != null ? calibrated : original;
            originalVals.add(original);
            effectiveVals.add(effective);
            weightVals.add(dto.weight());

            CalibrationMatrixResponse.Kpi k = new CalibrationMatrixResponse.Kpi();
            k.setKpiConfigId(dto.kpiConfigId());
            k.setKpiType(dto.kpiType());
            k.setIndicatorName(dto.indicatorName());
            k.setWeight(dto.weight());
            k.setOriginalScore(original);
            k.setCalibratedScore(calibrated);
            k.setScore(effective);
            k.setAssessorName(assessorName);
            k.setEvidenceUrl(s != null ? s.getEvidenceUrl() : null);
            kpis.add(k);
        }
        List<BigDecimal> normWeights = ScoreCalculator.normalizeWeights(weightVals);
        BigDecimal originalSubtotal = ScoreCalculator.weightedSum(originalVals, normWeights);
        BigDecimal adjustedSubtotal = ScoreCalculator.weightedSum(effectiveVals, normWeights);
        return new TaskTotals(originalSubtotal, adjustedSubtotal, kpis);
    }

    // 功能：按 task 单项改分——写 assessment_score.calibrated_score（不覆盖评估人原始分 score），
    //   追加 score_kpi_adjustment 审计（old/new），用同一 weightedSum 重算该项目任务小计返回；
    //   不触碰周期级 composite（assessment_result）计算逻辑
    @Transactional
    public AdjustKpiResult adjustKpi(String periodId, Long taskId, Long kpiConfigId, BigDecimal newScore, String reason) {
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
        if (taskId == null || kpiConfigId == null) {
            throw new BusinessException(400, "缺少 taskId 或 kpiConfigId");
        }

        AssessmentTask task = taskMapper.selectById(taskId);
        if (task == null || !periodId.equals(task.getPeriodId())) {
            throw new BusinessException(404, "考核任务不存在或不属于该周期: " + taskId);
        }
        if (!STATUS_SUBMITTED.equals(task.getStatus())) {
            throw new BusinessException(400, "仅已提交任务可校准");
        }

        AssessmentScore score = scoreMapper.selectOne(new LambdaQueryWrapper<AssessmentScore>()
                .eq(AssessmentScore::getTaskId, taskId)
                .eq(AssessmentScore::getKpiConfigId, kpiConfigId));
        if (score == null) {
            throw new BusinessException(404, "该指标评分不存在");
        }

        BigDecimal oldEffective = score.getCalibratedScore() != null ? score.getCalibratedScore() : score.getScore();
        BigDecimal normalized = newScore.setScale(1, RoundingMode.HALF_UP);
        score.setCalibratedScore(normalized);
        score.setUpdatedAt(LocalDateTime.now());
        int updated = scoreMapper.updateById(score); // @Version 乐观锁：版本过期返回 0
        if (updated == 0) {
            throw new BusinessException(409, "该指标已被他人修改，请刷新后重试");
        }

        ScoreKpiAdjustment audit = new ScoreKpiAdjustment();
        audit.setPeriodId(periodId);
        audit.setTaskId(taskId);
        audit.setKpiConfigId(kpiConfigId);
        audit.setAdjustedBy(currentOperatorId());
        audit.setOldScore(oldEffective);
        audit.setNewScore(normalized);
        audit.setReason(reason);
        audit.setCreatedAt(LocalDateTime.now());
        kpiAdjustmentMapper.insert(audit);

        // 重算该项目任务小计（读最新覆盖分）
        Map<Long, List<AssessmentScore>> scoresByTask = Map.of(taskId, scoreMapper.selectList(
                new LambdaQueryWrapper<AssessmentScore>().eq(AssessmentScore::getTaskId, taskId)));
        GroupTotals totals = computeGroupTotals(List.of(task), scoresByTask, null);

        // 项目小计落库（仅项目任务；职能任务无 project_code 跳过）
        if ("PROJECT".equals(task.getTaskType()) && StringUtils.hasText(task.getProjectCode())) {
            upsertProjectSubtotal(periodId, task.getAssesseeId(), task.getProjectCode(), task.getProjectStage(),
                    totals.originalSubtotal(), totals.adjustedSubtotal());
        }

        AdjustKpiResult result = new AdjustKpiResult();
        result.setTaskId(taskId);
        result.setOriginalSubtotal(totals.originalSubtotal());
        result.setAdjustedSubtotal(totals.adjustedSubtotal());
        return result;
    }

    // 功能：项目任务小计 upsert——已存在则更新（乐观锁），否则插入
    private void upsertProjectSubtotal(String periodId, String assesseeId, String projectCode, String projectStage,
                                       BigDecimal originalSubtotal, BigDecimal adjustedSubtotal) {
        AssessmentProjectSubtotal existing = projectSubtotalMapper.selectOne(
                new LambdaQueryWrapper<AssessmentProjectSubtotal>()
                        .eq(AssessmentProjectSubtotal::getPeriodId, periodId)
                        .eq(AssessmentProjectSubtotal::getAssesseeId, assesseeId)
                        .eq(AssessmentProjectSubtotal::getProjectCode, projectCode)
                        .eq(AssessmentProjectSubtotal::getProjectStage, projectStage)
                        .eq(AssessmentProjectSubtotal::getDeleted, 0));
        if (existing != null) {
            existing.setOriginalSubtotal(originalSubtotal);
            existing.setAdjustedSubtotal(adjustedSubtotal);
            existing.setUpdatedAt(LocalDateTime.now());
            projectSubtotalMapper.updateById(existing);
        } else {
            AssessmentProjectSubtotal s = new AssessmentProjectSubtotal();
            s.setPeriodId(periodId);
            s.setAssesseeId(assesseeId);
            s.setProjectCode(projectCode);
            s.setProjectStage(projectStage);
            s.setOriginalSubtotal(originalSubtotal);
            s.setAdjustedSubtotal(adjustedSubtotal);
            s.setDeleted(0);
            s.setCreatedAt(LocalDateTime.now());
            s.setUpdatedAt(LocalDateTime.now());
            projectSubtotalMapper.insert(s);
        }
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

    // 分组引用——键 + 展示名 + 组内任务（项目组单任务；职能组可多任务取均值）
    private record GroupRef(String key, String label, List<AssessmentTask> tasks) {}

    // 组小计——原始小计 + 校准后小计 + 逐 KPI 明细（首任务 KPI 集）
    private record GroupTotals(BigDecimal originalSubtotal, BigDecimal adjustedSubtotal, List<CalibrationMatrixResponse.Kpi> kpis) {}

    // 单任务小计——原始 + 校准后 + KPI 明细
    private record TaskTotals(BigDecimal originalSubtotal, BigDecimal adjustedSubtotal, List<CalibrationMatrixResponse.Kpi> kpis) {}
}
