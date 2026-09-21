// 模块用途：CalibrationService 单元测试——校准矩阵离群计算 + 行内改分乐观锁与审计
// 依赖文件：CalibrationService.java, AssessmentResultMapper.java, ScoreAdjustmentMapper.java, 各 Mapper
// 修改注意：@SpringBootTest + @Transactional（测试库 PostgreSQL，每个用例独立回滚）
package com.jifeng.assessment.calibration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.result.ScoreAdjustment;
import com.jifeng.assessment.result.ScoreAdjustmentMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CalibrationServiceTest {

    @Autowired private CalibrationService calibrationService;
    @Autowired private AssessmentResultMapper resultMapper;
    @Autowired private ScoreAdjustmentMapper adjustmentMapper;
    @Autowired private PeriodMapper periodMapper;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private ProjectMapper projectMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private ProjectRoleAssignmentMapper roleAssignmentMapper;
    @Autowired private ProjectConfirmationMapper projectConfirmationMapper;

    private static final String PERIOD = "PERIOD-CAL";
    private static final String PROJECT = "PRJ1";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // 功能：矩阵按项目归组并正确标记离群——[4.0,4.1,4.2,2.0] 中 2.0 偏离均值 >1σ，判 LOW 离群且置顶
    @Test
    void matrixShouldFlagLowOutlierAndSortFirst() {
        seedPeriod("CALIBRATING");
        seedProject();
        seedEmployee("ASSESSOR1");
        seedResult("EMP_A", "4.0000");
        seedResult("EMP_B", "4.1000");
        seedResult("EMP_C", "4.2000");
        seedResult("EMP_D", "2.0000");
        seedProjectTask("EMP_A");
        seedProjectTask("EMP_B");
        seedProjectTask("EMP_C");
        seedProjectTask("EMP_D");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        assertEquals(1, matrix.getSummary().size());
        CalibrationMatrixResponse.GroupSummary gs = matrix.getSummary().get(0);
        assertEquals("project:" + PROJECT, gs.getKey());
        assertEquals(4, gs.getCount());
        assertEquals(1, gs.getOutlierCount());

        assertEquals(4, matrix.getRows().size());
        // 离群置顶：2.0 员工排第一，且方向 LOW
        CalibrationMatrixResponse.Row first = matrix.getRows().get(0);
        assertEquals("EMP_D", first.getAssesseeId());
        assertTrue(first.isOutlier());
        assertEquals("LOW", first.getDirection());
        // 非离群行不被误标
        assertTrue(matrix.getRows().stream().filter(r -> !r.getAssesseeId().equals("EMP_D")).noneMatch(CalibrationMatrixResponse.Row::isOutlier));
    }

    // 功能：仅职能任务的员工归「职能」组，不混入项目组
    @Test
    void matrixShouldGroupFunctionalOnlyEmployeeSeparately() {
        seedPeriod("CALIBRATING");
        seedProject();
        seedEmployee("ASSESSOR1");
        seedResult("EMP_P", "4.0000");
        seedResult("EMP_F", "3.5000");
        seedProjectTask("EMP_P");
        seedFunctionalTask("EMP_F");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        assertEquals(2, matrix.getSummary().size());
        CalibrationMatrixResponse.Row funcRow = matrix.getRows().stream()
                .filter(r -> r.getAssesseeId().equals("EMP_F")).findFirst().orElseThrow();
        assertEquals("functional", funcRow.getGroupKey());
        assertEquals("职能考核", funcRow.getGroupLabel());
    }

    // 功能：半提交员工只出现在未提交暗行，不与正常结果行重复（后端严格完整性语义下行列互斥）
    @Test
    void matrixShouldNotDuplicatePartialSubmission() {
        seedPeriod("CALIBRATING");
        seedProject();
        seedEmployee("ASSESSOR1");
        // 全提交员工：有结果行 + SUBMITTED 项目任务 → 出现在正常行
        seedResult("EMP_FULL", "4.0000");
        seedProjectTask("EMP_FULL");
        // 半提交员工：无结果行，仅一条 IN_PROGRESS 任务 → 归入未提交暗行
        seedEmployee("EMP_HALF");
        seedPendingTask("EMP_HALF");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        assertEquals(1, matrix.getRows().size());
        assertEquals("EMP_FULL", matrix.getRows().get(0).getAssesseeId());
        assertEquals(1, matrix.getUnsubmitted().size());
        assertEquals("EMP_HALF", matrix.getUnsubmitted().get(0).getAssesseeId());
        assertEquals(1, matrix.getUnsubmittedCount());
    }

    // 功能：改分写 adjusted_score + 追加审计行（无鉴权上下文中 adjusted_by 回退 system）
    @Test
    void adjustShouldUpdateScoreAndWriteAudit() {
        seedPeriod("CALIBRATING");
        seedResult("EMP_A", "3.7000");

        calibrationService.adjust(PERIOD, "EMP_A", new BigDecimal("4.5000"), "评估人评分尺度偏差");

        AssessmentResult result = resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, PERIOD)
                .eq(AssessmentResult::getAssesseeId, "EMP_A"));
        assertEquals(0, new BigDecimal("4.5000").compareTo(result.getAdjustedScore()));
        assertEquals(0, new BigDecimal("3.7000").compareTo(result.getOriginalScore()));

        ScoreAdjustment audit = adjustmentMapper.selectOne(new LambdaQueryWrapper<ScoreAdjustment>()
                .eq(ScoreAdjustment::getAssessmentResultId, result.getId()));
        assertNotNull(audit);
        assertEquals(0, new BigDecimal("3.7000").compareTo(audit.getOldScore()));
        assertEquals(0, new BigDecimal("4.5000").compareTo(audit.getNewScore()));
        assertEquals("评估人评分尺度偏差", audit.getReason());
        assertEquals("system", audit.getAdjustedBy());
    }

    // 功能：非 CALIBRATING 周期拒绝改分——400 业务异常
    @Test
    void adjustShouldRejectNonCalibratingPeriod() {
        seedPeriod("ONGOING");
        seedResult("EMP_A", "3.7000");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                calibrationService.adjust(PERIOD, "EMP_A", new BigDecimal("4.5000"), "评估人评分尺度偏差"));
        assertEquals(400, ex.getCode());
    }

    // 功能：总裁调用校准矩阵只返回自己作为主总裁的项目的评分明细，其它项目数据不泄露
    @Test
    void presidentShouldOnlySeeOwnProjectsInMatrix() {
        seedPeriod("CALIBRATING");
        seedEmployee("ASSESSOR1");
        seedEmployee("EMP_PRES_OWNER");
        seedEmployee("EMP_PRES_OTHER");
        seedUser("U_PRES_OWNER", "pres_cal", "EMP_PRES_OWNER");

        seedProject("PRJ_OWN", "P2");
        seedProject("PRJ_OTHER", "P2");
        seedPresidentAssignment("PRJ_OWN", "P2", "EMP_PRES_OWNER");
        seedPresidentAssignment("PRJ_OTHER", "P2", "EMP_PRES_OTHER");

        // 自有项目员工 EMP_A；他人项目员工 EMP_B
        seedResult("EMP_A", "4.0000");
        seedResult("EMP_B", "3.0000");
        seedProjectTask("EMP_A", "PRJ_OWN", "P2");
        seedProjectTask("EMP_B", "PRJ_OTHER", "P2");

        auth("pres_cal", "总裁");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        List<String> assessees = matrix.getRows().stream()
                .map(CalibrationMatrixResponse.Row::getAssesseeId).toList();
        assertEquals(1, assessees.size());
        assertTrue(assessees.contains("EMP_A"));
        assertFalse(assessees.contains("EMP_B"));
        // 汇总带仅含自有项目分组
        assertEquals(1, matrix.getSummary().size());
        assertEquals("project:PRJ_OWN", matrix.getSummary().get(0).getKey());
    }

    // 功能：校准矩阵回填总裁确认状态与退回意见——被退回人员行带 RETURNED 状态 + returnReason（问题2）
    @Test
    void matrixShouldReturnConfirmationStatusAndReturnReason() {
        seedPeriod("CALIBRATING");
        seedProject();
        seedEmployee("ASSESSOR1");
        seedResult("EMP_A", "4.0000");
        seedProjectTask("EMP_A");
        seedConfirmation("EMP_A", "RETURNED", "结果有误");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        CalibrationMatrixResponse.Row row = matrix.getRows().stream()
                .filter(r -> "EMP_A".equals(r.getAssesseeId())).findFirst().orElseThrow();
        assertEquals("RETURNED", row.getConfirmationStatus());
        assertEquals("结果有误", row.getReturnReason());
    }

    // ================= 辅助：种子数据 =================

    private void seedPeriod(String status) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(PERIOD);
        period.setPeriodName("校准测试周期");
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 6, 30));
        period.setStatus(status);
        periodMapper.insert(period);
    }

    private void seedProject() {
        Project project = new Project();
        project.setProjectCode(PROJECT);
        project.setProjectName("项目一");
        project.setProjectStage("P2");
        project.setStatus("ACTIVE");
        project.setStageConfirmed(false);
        projectMapper.insert(project);
    }

    private void seedEmployee(String employeeId) {
        Employee e = new Employee();
        e.setEmployeeId(employeeId);
        e.setName("员工" + employeeId);
        e.setEmail(employeeId + "@test.com");
        e.setCategory("研发技术类");
        e.setPosition("整椅研发岗");
        e.setOrgName("信息部");
        e.setStatus("ACTIVE");
        employeeMapper.insert(e);
    }

    private void seedResult(String assesseeId, String originalScore) {
        seedEmployee(assesseeId);

        AssessmentResult result = new AssessmentResult();
        result.setPeriodId(PERIOD);
        result.setAssesseeId(assesseeId);
        result.setOriginalScore(new BigDecimal(originalScore));
        result.setAdjustedScore(new BigDecimal(originalScore));
        result.setDeleted(0);
        result.setVersion(0L);
        resultMapper.insert(result);
    }

    private void seedConfirmation(String assesseeId, String status, String returnReason) {
        ProjectConfirmation c = new ProjectConfirmation();
        c.setPeriodId(PERIOD);
        c.setProjectCode(PROJECT);
        c.setAssesseeId(assesseeId);
        c.setStatus(status);
        c.setReturnCount(0);
        c.setReturnReason(returnReason);
        projectConfirmationMapper.insert(c);
    }

    private void seedProjectTask(String assesseeId) {
        seedTask(assesseeId, "PROJECT");
    }

    private void seedFunctionalTask(String assesseeId) {
        seedTask(assesseeId, "FUNCTIONAL");
    }

    private void seedPendingTask(String assesseeId) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(PERIOD);
        task.setAssessorId("ASSESSOR1");
        task.setAssesseeId(assesseeId);
        task.setProjectCode(PROJECT);
        task.setProjectStage("P2");
        task.setTaskType("PROJECT");
        task.setStatus("IN_PROGRESS");
        taskMapper.insert(task);
    }

    private void seedTask(String assesseeId, String taskType) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(PERIOD);
        task.setAssessorId("ASSESSOR1");
        task.setAssesseeId(assesseeId);
        task.setProjectCode("PROJECT".equals(taskType) ? PROJECT : null);
        task.setProjectStage("PROJECT".equals(taskType) ? "P2" : null);
        task.setTaskType(taskType);
        task.setStatus("SUBMITTED");
        taskMapper.insert(task);
    }

    // 辅助：插入指定编码/阶段的项目
    private void seedProject(String code, String stage) {
        Project project = new Project();
        project.setProjectCode(code);
        project.setProjectName("项目" + code);
        project.setProjectStage(stage);
        project.setStatus("ACTIVE");
        project.setStageConfirmed(false);
        projectMapper.insert(project);
    }

    // 辅助：插入指定项目编码/阶段的 SUBMITTED 项目任务
    private void seedProjectTask(String assesseeId, String projectCode, String projectStage) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(PERIOD);
        task.setAssessorId("ASSESSOR1");
        task.setAssesseeId(assesseeId);
        task.setProjectCode(projectCode);
        task.setProjectStage(projectStage);
        task.setTaskType("PROJECT");
        task.setStatus("SUBMITTED");
        taskMapper.insert(task);
    }

    // 辅助：插入主总裁角色分配（PRESIDENT 角色已在 V27 种子，无需重复插 project_role）
    private void seedPresidentAssignment(String projectCode, String stage, String employeeId) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode("PRESIDENT");
        a.setEmployeeId(employeeId);
        a.setIsPrimary(true);
        a.setDeleted(0);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：插入系统用户（绑定员工）
    private void seedUser(String userId, String username, String employeeId) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUsername(username);
        user.setPasswordHash("test-hash");
        user.setEmployeeId(employeeId);
        user.setEnabled(true);
        sysUserMapper.insert(user);
    }

    // 辅助：设置当前登录用户及其角色
    private void auth(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
