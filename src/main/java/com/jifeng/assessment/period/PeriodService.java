// 模块用途：考核周期业务逻辑——CRUD、活跃周期唯一约束、编辑校验、关闭周期
// 依赖文件：PeriodMapper.java, AssessmentPeriod.java
// 修改注意：同一时间只能有一个非COMPLETED周期，创建时自动生成periodId
package com.jifeng.assessment.period;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jifeng.assessment.calibration.AssessmentResult;
import com.jifeng.assessment.calibration.AssessmentResultMapper;
import com.jifeng.assessment.calibration.CalibrationSubmission;
import com.jifeng.assessment.calibration.CalibrationSubmissionMapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.result.ResultService;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.DiscrepancyLog;
import com.jifeng.assessment.task.DiscrepancyLogMapper;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PeriodService {

    private final PeriodMapper periodMapper;
    private final ResultService resultService;
    private final TaskMapper taskMapper;
    private final ProjectConfirmationMapper projectConfirmationMapper;
    private final ProjectRoleAssignmentMapper roleAssignmentMapper;
    private final DiscrepancyLogMapper discrepancyLogMapper;
    private final AssessmentResultMapper assessmentResultMapper;
    private final CalibrationSubmissionMapper calibrationSubmissionMapper;
    private final SysUserMapper sysUserMapper;

    private static final String COMPLETED = "COMPLETED";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String CALIBRATING = "CALIBRATING";
    private static final String ONGOING = "ONGOING";
    private static final String INIT = "INIT";
    private static final String ROLE_PRESIDENT = "PRESIDENT";
    private static final String ROLE_PD = "PD";
    private static final String DISCREPANCY_NO_PRESIDENT = "NO_PRESIDENT";

    // 功能：查询考核周期列表，支持按status筛选
    public List<AssessmentPeriod> listPeriods(String status) {
        LambdaQueryWrapper<AssessmentPeriod> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(AssessmentPeriod::getStatus, status);
        }
        wrapper.orderByDesc(AssessmentPeriod::getCreatedAt);
        return periodMapper.selectList(wrapper);
    }

    // 功能：创建考核周期——自动生成periodId，校验无活跃周期
    @Transactional
    public AssessmentPeriod createPeriod(AssessmentPeriod period) {
        if (period.getStartDate() != null && period.getEndDate() != null
                && period.getStartDate().isAfter(period.getEndDate())) {
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        }
        // 活跃周期唯一约束：不能存在非COMPLETED的周期
        long activeCount = periodMapper.selectCount(
                new LambdaQueryWrapper<AssessmentPeriod>().ne(AssessmentPeriod::getStatus, COMPLETED));
        if (activeCount > 0) {
            throw new BusinessException(409, "当前已有未关闭的考核周期，请先关闭后再创建新周期");
        }
        period.setPeriodId(UUID.randomUUID().toString().replace("-", ""));
        period.setStatus(INIT);
        periodMapper.insert(period);
        return periodMapper.selectById(period.getPeriodId());
    }

    // 功能：编辑考核周期——仅INIT状态可修改名称和起止日期
    @Transactional
    public AssessmentPeriod updatePeriod(String periodId, AssessmentPeriod update) {
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }
        if (!INIT.equals(period.getStatus())) {
            throw new BusinessException(400, "仅未开始的考核周期可编辑");
        }
        if (update.getStartDate() != null && update.getEndDate() != null
                && update.getStartDate().isAfter(update.getEndDate())) {
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        }
        if (update.getPeriodName() != null) {
            period.setPeriodName(update.getPeriodName());
        }
        if (update.getStartDate() != null) {
            period.setStartDate(update.getStartDate());
        }
        if (update.getEndDate() != null) {
            period.setEndDate(update.getEndDate());
        }
        period.setUpdatedAt(LocalDateTime.now());
        periodMapper.updateById(period);
        return periodMapper.selectById(periodId);
    }

    // 功能：进入校准——ONGOING→CALIBRATING 原子翻转，打分结束进入校准确认阶段
    @Transactional
    public AssessmentPeriod enterCalibration(String periodId) {
        requirePeriod(periodId);
        int updated = periodMapper.updateStatus(periodId, ONGOING, CALIBRATING);
        if (updated == 0) {
            throw new BusinessException(400, "仅进行中的周期可进入校准");
        }
        // 进入校准即生成结果行：仅聚合 SUBMITTED 任务，未提交员工跳空不生成（完整性软门不阻断）
        resultService.generateResults(periodId);
        // 生成项目确认行（逐项目 PENDING）+ 未分配总裁项目差异
        generateProjectConfirmations(periodId);
        return periodMapper.selectById(periodId);
    }

    // 功能：发布结果——CONFIRMED→PUBLISHED 原子翻转，总裁逐项目确认全部通过后由 ADMIN 触发
    @Transactional
    public AssessmentPeriod publishPeriod(String periodId) {
        requirePeriod(periodId);
        int updated = periodMapper.updateStatus(periodId, CONFIRMED, PUBLISHED);
        if (updated == 0) {
            throw new BusinessException(400, "仅已确认的周期可发布");
        }
        return periodMapper.selectById(periodId);
    }

    // 功能：尝试周期级确认——CALIBRATING→CONFIRMED 原子翻转（单条 UPDATE WHERE status='CALIBRATING'），
    //   由 PresidentService 逐项目确认全部通过后调用；返回是否翻转成功（并发下先到先得）
    @Transactional
    public boolean tryConfirmPeriod(String periodId) {
        int updated = periodMapper.updateStatus(periodId, CALIBRATING, CONFIRMED);
        if (updated > 0) {
            resultService.generateResults(periodId);
            return true;
        }
        return false;
    }

    // 功能：关闭考核周期——仅 PUBLISHED→COMPLETED，需先发布结果（防旁路）
    @Transactional
    public AssessmentPeriod closePeriod(String periodId) {
        AssessmentPeriod period = requirePeriod(periodId);
        if (COMPLETED.equals(period.getStatus())) {
            throw new BusinessException(400, "该考核周期已关闭，无需重复操作");
        }
        if (!PUBLISHED.equals(period.getStatus())) {
            throw new BusinessException(400, "仅已发布的周期可关闭，请先发布结果");
        }
        int updated = periodMapper.updateStatus(periodId, PUBLISHED, COMPLETED);
        if (updated == 0) {
            throw new BusinessException(409, "周期状态已变更，请刷新后重试");
        }
        return periodMapper.selectById(periodId);
    }

    // 功能：强制关闭（abort）——任意非COMPLETED状态直接置为COMPLETED，作为异常周期的逃生出口
    @Transactional
    public AssessmentPeriod abortPeriod(String periodId) {
        requirePeriod(periodId);
        int updated = periodMapper.forceComplete(periodId);
        if (updated == 0) {
            throw new BusinessException(400, "该考核周期已关闭，无需重复操作");
        }
        return periodMapper.selectById(periodId);
    }

    // 功能：PD 项目级提交校准——仅 CALIBRATING 周期可提交；只写当前 PD 主 PD 负责的项目
    //   （ADMIN 提交全部涉及项目），逐项目 upsert calibration_submission，重复提交更新 submitted_at（幂等）。
    //   提交后重置本 PD 名下项目的 RETURNED 确认行为 PENDING，并派生刷新周期级 calibration_submitted_at
    @Transactional
    public AssessmentPeriod submitCalibration(String periodId) {
        AssessmentPeriod period = requirePeriod(periodId);
        if (!CALIBRATING.equals(period.getStatus())) {
            throw new BusinessException(400, "仅校准中的周期可提交校准");
        }
        Set<String> targetKeys = targetProjectKeys(periodId);
        String operatorId = currentEmployeeId();
        LocalDateTime now = LocalDateTime.now();
        for (String key : targetKeys) {
            upsertSubmission(periodId, key, operatorId, now);
        }
        // 重新提交时 RETURNED→PENDING：仅作用于本 PD 名下项目（跨 PD 互不影响）
        resetReturnedToPending(periodId, targetKeys);
        refreshCalibrationSubmittedAt(periodId);
        return periodMapper.selectById(periodId);
    }

    // 功能：总裁退回某项目后清空该项目的校准提交记录（unsubmit），并派生刷新周期级提交时间戳——
    //   只影响被退回项目，其它 PD 的已提交记录不受影响（项目级粒度）
    @Transactional
    public void clearProjectSubmission(String periodId, String projectCode) {
        calibrationSubmissionMapper.update(null, new LambdaUpdateWrapper<CalibrationSubmission>()
                .eq(CalibrationSubmission::getPeriodId, periodId)
                .eq(CalibrationSubmission::getProjectCode, projectCode)
                .set(CalibrationSubmission::getSubmittedAt, null)
                .set(CalibrationSubmission::getSubmittedByEmployeeId, null));
        refreshCalibrationSubmittedAt(periodId);
    }

    // 功能：确定本次提交的目标 (code|stage) 项目键——PD 只提交自己主 PD 负责且本周期涉及的项目；
    //   ADMIN 提交全部涉及项目；未登录/未绑定员工时提交空集（无可提交项目）
    private Set<String> targetProjectKeys(String periodId) {
        if (hasRole("ADMIN")) {
            return involvedProjectKeys(periodId);
        }
        String employeeId = currentEmployeeId();
        if (employeeId == null) {
            return Set.of();
        }
        Set<String> involved = involvedProjectKeys(periodId);
        return ownedPdProjectKeys(employeeId).stream()
                .filter(involved::contains)
                .collect(Collectors.toSet());
    }

    // 功能：逐项目 upsert 校准提交记录——已存在则更新 submitted_at（幂等重复提交），不存在则插入
    private void upsertSubmission(String periodId, String key, String operatorId, LocalDateTime now) {
        int sep = key.indexOf('|');
        String code = sep >= 0 ? key.substring(0, sep) : key;
        String stage = sep >= 0 ? key.substring(sep + 1) : "";
        CalibrationSubmission existing = calibrationSubmissionMapper.selectOne(
                new LambdaQueryWrapper<CalibrationSubmission>()
                        .eq(CalibrationSubmission::getPeriodId, periodId)
                        .eq(CalibrationSubmission::getProjectCode, code)
                        .eq(CalibrationSubmission::getProjectStage, stage));
        if (existing != null) {
            existing.setSubmittedByEmployeeId(operatorId);
            existing.setSubmittedAt(now);
            existing.setUpdatedAt(now);
            calibrationSubmissionMapper.updateById(existing);
        } else {
            CalibrationSubmission submission = new CalibrationSubmission();
            submission.setPeriodId(periodId);
            submission.setProjectCode(code);
            submission.setProjectStage(stage);
            submission.setSubmittedByEmployeeId(operatorId);
            submission.setSubmittedAt(now);
            submission.setCreatedAt(now);
            submission.setUpdatedAt(now);
            calibrationSubmissionMapper.insert(submission);
        }
    }

    // 功能：派生刷新周期级校准提交时间戳——本周期所有涉及项目均已提交时置非空，否则置 NULL
    private void refreshCalibrationSubmittedAt(String periodId) {
        Set<String> involved = involvedProjectKeys(periodId);
        boolean allSubmitted = !involved.isEmpty()
                && submittedProjectKeys(periodId).containsAll(involved);
        AssessmentPeriod period = periodMapper.selectById(periodId);
        boolean flagSet = period.getCalibrationSubmittedAt() != null;
        if (allSubmitted && !flagSet) {
            periodMapper.update(null, new LambdaUpdateWrapper<AssessmentPeriod>()
                    .eq(AssessmentPeriod::getPeriodId, periodId)
                    .set(AssessmentPeriod::getCalibrationSubmittedAt, LocalDateTime.now()));
        } else if (!allSubmitted && flagSet) {
            periodMapper.update(null, new LambdaUpdateWrapper<AssessmentPeriod>()
                    .eq(AssessmentPeriod::getPeriodId, periodId)
                    .set(AssessmentPeriod::getCalibrationSubmittedAt, null));
        }
    }

    // 功能：重新提交时 RETURNED→PENDING——清除退回原因，保留退回计数（与 PresidentService.resubmit 同语义，
    //   但仅作用于本次提交的目标项目，跨 PD 互不影响）
    private void resetReturnedToPending(String periodId, Set<String> targetKeys) {
        if (targetKeys.isEmpty()) {
            return;
        }
        List<String> codes = targetKeys.stream()
                .map(this::codeFromKey)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        List<ProjectConfirmation> returned = projectConfirmationMapper.selectList(
                new LambdaQueryWrapper<ProjectConfirmation>()
                        .eq(ProjectConfirmation::getPeriodId, periodId)
                        .in(ProjectConfirmation::getProjectCode, codes)
                        .eq(ProjectConfirmation::getStatus, "RETURNED"));
        for (ProjectConfirmation c : returned) {
            c.setStatus("PENDING");
            c.setReturnReason(null);
            c.setUpdatedAt(LocalDateTime.now());
            projectConfirmationMapper.updateById(c);
        }
    }

    // 功能：本周期涉及的 (code|stage) 项目键——来自 SUBMITTED PROJECT 任务（与校准矩阵分组同源）
    private Set<String> involvedProjectKeys(String periodId) {
        return taskMapper.selectList(new LambdaQueryWrapper<AssessmentTask>()
                        .eq(AssessmentTask::getPeriodId, periodId)
                        .eq(AssessmentTask::getTaskType, "PROJECT")
                        .eq(AssessmentTask::getStatus, "SUBMITTED")
                        .isNotNull(AssessmentTask::getProjectCode)
                        .ne(AssessmentTask::getProjectCode, ""))
                .stream()
                .map(this::taskKey)
                .collect(Collectors.toSet());
    }

    // 功能：本周期已提交的 (code|stage) 项目键——来自 calibration_submission（submitted_at 非空）
    private Set<String> submittedProjectKeys(String periodId) {
        return calibrationSubmissionMapper.selectList(new LambdaQueryWrapper<CalibrationSubmission>()
                        .eq(CalibrationSubmission::getPeriodId, periodId)
                        .isNotNull(CalibrationSubmission::getSubmittedAt))
                .stream()
                .map(this::submissionKey)
                .collect(Collectors.toSet());
    }

    // 功能：任务 → (code|stage) 复合键
    private String taskKey(AssessmentTask task) {
        return (task.getProjectCode() == null ? "" : task.getProjectCode())
                + "|" + (task.getProjectStage() == null ? "" : task.getProjectStage());
    }

    // 功能：校准提交记录 → (code|stage) 复合键
    private String submissionKey(CalibrationSubmission s) {
        return (s.getProjectCode() == null ? "" : s.getProjectCode())
                + "|" + (s.getProjectStage() == null ? "" : s.getProjectStage());
    }

    // 功能：从 (code|stage) 复合键反解 projectCode
    private String codeFromKey(String key) {
        if (key == null) {
            return null;
        }
        int sep = key.indexOf('|');
        return sep >= 0 ? key.substring(0, sep) : key;
    }

    // 功能：反查当前用户主 PD 负责的 (code|stage) 项目键集合——project_role_assignment（PD 且 is_primary 且未删除）
    private Set<String> ownedPdProjectKeys(String employeeId) {
        return roleAssignmentMapper.selectList(new LambdaQueryWrapper<ProjectRoleAssignment>()
                        .eq(ProjectRoleAssignment::getEmployeeId, employeeId)
                        .eq(ProjectRoleAssignment::getProjectRoleCode, ROLE_PD)
                        .eq(ProjectRoleAssignment::getIsPrimary, true)
                        .eq(ProjectRoleAssignment::getDeleted, 0))
                .stream()
                .map(a -> (a.getProjectCode() == null ? "" : a.getProjectCode())
                        + "|" + (a.getProjectStage() == null ? "" : a.getProjectStage()))
                .collect(Collectors.toSet());
    }

    // 功能：判断当前用户是否具有指定角色——检查权限列表中是否含 ROLE_<role>
    private boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
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

    // 功能：结果可见性——仅 PUBLISHED（已发布）或 COMPLETED（已归档）时员工可查看最终结果；CONFIRMED 待发布不可见
    public boolean isResultVisible(String periodId) {
        if (!StringUtils.hasText(periodId)) {
            return false;
        }
        AssessmentPeriod period = periodMapper.selectById(periodId);
        return period != null && PeriodStatusPolicy.isResultVisible(period.getStatus());
    }

    // 功能：加载周期，不存在抛404
    private AssessmentPeriod requirePeriod(String periodId) {
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }
        return period;
    }

    // 功能：进入校准时生成项目确认行——按「项目 × 员工」逐人插 PENDING：
    //   员工来源 = assessment_result（本周期已生成结果的 distinct assessee_id），
    //   项目归属 = 该员工全部 SUBMITTED PROJECT 任务的 distinct project_code（多项目员工逐项目生成确认行，
    //   与校准矩阵「project:code|stage」分组口径一致，避免 minBy(id) 丢项目）；未分配主总裁的项目另写 NO_PRESIDENT 差异
    private void generateProjectConfirmations(String periodId) {
        // 员工 → 项目集合 映射：取员工全部 SUBMITTED PROJECT 任务的 distinct project_code，
        //   与校准矩阵分组口径一致（此前取 minBy(id) 单一 code，多项目员工丢项目，导致确认状态回填落空）
        Map<String, Set<String>> projectByAssessee = taskMapper.selectList(
                        new LambdaQueryWrapper<AssessmentTask>()
                                .eq(AssessmentTask::getPeriodId, periodId)
                                .eq(AssessmentTask::getTaskType, "PROJECT")
                                .eq(AssessmentTask::getStatus, "SUBMITTED")
                                .isNotNull(AssessmentTask::getProjectCode)
                                .ne(AssessmentTask::getProjectCode, ""))
                .stream()
                .collect(Collectors.groupingBy(
                        AssessmentTask::getAssesseeId,
                        Collectors.mapping(AssessmentTask::getProjectCode,
                                Collectors.toCollection(LinkedHashSet::new))));

        // 已生成结果（全部 SUBMITTED）的员工 = 需总裁逐人确认的人员
        List<String> assesseeIds = assessmentResultMapper.selectList(
                        new LambdaQueryWrapper<AssessmentResult>()
                                .eq(AssessmentResult::getPeriodId, periodId))
                .stream()
                .map(AssessmentResult::getAssesseeId)
                .distinct()
                .toList();

        // 逐人逐项目插入 PENDING 确认行（幂等：已存在 (period, project, assessee) 行则跳过）
        for (String assesseeId : assesseeIds) {
            Set<String> projectCodes = projectByAssessee.get(assesseeId);
            if (projectCodes == null || projectCodes.isEmpty()) {
                continue; // 纯职能员工无项目，不参与项目确认
            }
            for (String projectCode : projectCodes) {
                Long existing = projectConfirmationMapper.selectCount(
                        new LambdaQueryWrapper<ProjectConfirmation>()
                                .eq(ProjectConfirmation::getPeriodId, periodId)
                                .eq(ProjectConfirmation::getProjectCode, projectCode)
                                .eq(ProjectConfirmation::getAssesseeId, assesseeId));
                if (existing != null && existing > 0) {
                    continue;
                }
                ProjectConfirmation confirmation = new ProjectConfirmation();
                confirmation.setPeriodId(periodId);
                confirmation.setProjectCode(projectCode);
                confirmation.setAssesseeId(assesseeId);
                confirmation.setStatus("PENDING");
                confirmation.setReturnCount(0);
                confirmation.setCreatedAt(LocalDateTime.now());
                confirmation.setUpdatedAt(LocalDateTime.now());
                projectConfirmationMapper.insert(confirmation);
            }
        }

        // 未分配主总裁的项目写 NO_PRESIDENT 差异（按项目去重，保留配置完整性告警语义）
        Set<String> projectCodes = taskMapper.selectList(
                        new LambdaQueryWrapper<AssessmentTask>()
                                .eq(AssessmentTask::getPeriodId, periodId)
                                .isNotNull(AssessmentTask::getProjectCode)
                                .ne(AssessmentTask::getProjectCode, ""))
                .stream()
                .map(AssessmentTask::getProjectCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String projectCode : projectCodes) {
            List<String> presidents = resolvePrimaryPresidents(projectCode);
            if (presidents.size() == 1) {
                continue;
            }
            DiscrepancyLog log = new DiscrepancyLog();
            log.setPeriodId(periodId);
            log.setEmployeeId("");
            log.setProjectCode(projectCode);
            log.setType(DISCREPANCY_NO_PRESIDENT);
            log.setDetail(presidents.isEmpty()
                    ? "项目" + projectCode + "未分配主总裁，无法进行项目确认"
                    : "项目" + projectCode + "存在多个主总裁(" + String.join(",", presidents) + ")，无法进行项目确认");
            log.setResolved(false);
            log.setCreatedAt(LocalDateTime.now());
            log.setUpdatedAt(LocalDateTime.now());
            discrepancyLogMapper.insert(log);
        }
    }

    // 功能：悲观锁锁定周期行——SELECT ... FOR UPDATE（须在事务内调用），串行化同周期写，
    //   消除 PresidentService「selectCount→tryConfirmPeriod」分离导致的并发漏判
    public void lockPeriod(String periodId) {
        periodMapper.selectByIdForUpdate(periodId);
    }

    // 功能：反查项目主总裁工号列表（所有阶段去重）——project_role_assignment（PRESIDENT 且 is_primary 且未删除）
    //   用于检测「一项目多主总裁」冲突（跨阶段分配不一致）
    public List<String> resolvePrimaryPresidents(String projectCode) {
        return roleAssignmentMapper.selectList(
                        new LambdaQueryWrapper<ProjectRoleAssignment>()
                                .eq(ProjectRoleAssignment::getProjectCode, projectCode)
                                .eq(ProjectRoleAssignment::getProjectRoleCode, ROLE_PRESIDENT)
                                .eq(ProjectRoleAssignment::getIsPrimary, true)
                                .eq(ProjectRoleAssignment::getDeleted, 0))
                .stream()
                .map(ProjectRoleAssignment::getEmployeeId)
                .distinct()
                .toList();
    }

    // 功能：反查项目主总裁工号——所有阶段主总裁一致时返回该工号；无主总裁或多主总裁冲突时返回 null
    //   （一项目一主总裁：跨阶段分配不一致视为异常，由 NO_PRESIDENT 差异兜底，不静默取首个）
    public String resolvePrimaryPresident(String projectCode) {
        List<String> presidents = resolvePrimaryPresidents(projectCode);
        return presidents.size() == 1 ? presidents.get(0) : null;
    }

    // 功能：校验周期未关闭——周期已 COMPLETED 时拒绝所有写操作（评分/审批/提交参与/上传凭证等）
    // 返回400而非403：这是业务状态锁，不是权限问题
    public void assertNotCompleted(String periodId, String action) {
        if (!StringUtils.hasText(periodId)) {
            return; // 无周期信息的记录（历史遗留）不拦截
        }
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period != null && COMPLETED.equals(period.getStatus())) {
            throw new BusinessException(400, "考核周期已关闭，不可再" + action);
        }
    }

    // 功能：校验周期已发起且未关闭——评分/开始评分等操作要求周期必须为 ONGOING；
    //   COMPLETED 抛「已关闭」，INIT/CALIBRATING 等非 ONGOING 状态抛「尚未发起」
    public void assertOngoing(String periodId, String action) {
        if (!StringUtils.hasText(periodId)) {
            return; // 无周期信息的记录（历史遗留）不拦截
        }
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            return;
        }
        if (COMPLETED.equals(period.getStatus())) {
            throw new BusinessException(400, "考核周期已关闭，不可再" + action);
        }
        if (!ONGOING.equals(period.getStatus())) {
            throw new BusinessException(400, "考核尚未发起，不可" + action);
        }
    }

    // 功能：校验周期可填写/审批项目参与——仅 INIT 与 ONGOING 放行（INIT 期参与由 launch 统一生成任务）；
    //   COMPLETED 抛「已关闭」，其余（CALIBRATING/CONFIRMED/PUBLISHED）抛「已进入校准/确认」，冻结参与写操作
    public void assertParticipatable(String periodId, String action) {
        if (!StringUtils.hasText(periodId)) {
            return; // 无周期信息的记录（历史遗留）不拦截
        }
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            return;
        }
        String status = period.getStatus();
        if (PeriodStatusPolicy.isParticipatable(status)) {
            return; // INIT / ONGOING 放行
        }
        if (PeriodStatusPolicy.COMPLETED.equals(status)) {
            throw new BusinessException(400, "考核周期已关闭，不可再" + action);
        }
        throw new BusinessException(400, "考核已进入校准/确认，不可再" + action);
    }
}
