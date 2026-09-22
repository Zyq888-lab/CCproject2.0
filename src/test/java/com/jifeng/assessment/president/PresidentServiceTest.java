// 模块用途：PresidentService 单元测试——覆盖确认清单过滤、单项目通过/退回、退回上限、PD 重提交
// 依赖文件：PresidentService.java, ProjectConfirmation.java, ProjectRoleAssignment.java
// 修改注意：@SpringBootTest + @Transactional；周期级翻转依赖 PeriodService.tryConfirmPeriod 原子 UPDATE
package com.jifeng.assessment.president;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.calibration.CalibrationSubmission;
import com.jifeng.assessment.calibration.CalibrationSubmissionMapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PresidentServiceTest {

    @Autowired
    private PresidentService presidentService;

    @Autowired
    private PeriodMapper periodMapper;

    @Autowired
    private ProjectConfirmationMapper projectConfirmationMapper;

    @Autowired
    private ProjectRoleAssignmentMapper roleAssignmentMapper;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ProjectRoleMapper projectRoleMapper;

    @Autowired
    private CalibrationSubmissionMapper calibrationSubmissionMapper;

    @Autowired
    private TaskMapper taskMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // 功能：单项目退回不影响其它项目——退回其一，其它仍 PENDING，周期仍 CALIBRATING
    @Test
    void returnProjectShouldNotAffectOthers() {
        seedEmployee("EMP_PRES_1");
        seedUser("U_PRES_1", "pres1", "EMP_PRES_1");
        seedProject("PRJ_1", "P2");
        seedProject("PRJ_2", "P2");
        seedPeriod("PERIOD_R1", "CALIBRATING");
        seedAssignment("PRJ_1", "P2", "PRESIDENT", "EMP_PRES_1", true);
        seedAssignment("PRJ_2", "P2", "PRESIDENT", "EMP_PRES_1", true);
        Long id1 = seedConfirmation("PERIOD_R1", "PRJ_1", "PENDING", 0);
        Long id2 = seedConfirmation("PERIOD_R1", "PRJ_2", "PENDING", 0);

        auth("pres1", "总裁");

        presidentService.returnProject(id1, "结果有误");

        assertEquals("RETURNED", projectConfirmationMapper.selectById(id1).getStatus());
        assertEquals("PENDING", projectConfirmationMapper.selectById(id2).getStatus());
        assertEquals("CALIBRATING", periodMapper.selectById("PERIOD_R1").getStatus());
    }

    // 功能：单人员退回不影响同项目其他人——退回其一，同项目另一人仍 PENDING，其它项目仍 PENDING
    @Test
    void returnProjectShouldNotAffectSameProjectOthers() {
        seedEmployee("EMP_PRES_11");
        seedUser("U_PRES_11", "pres11", "EMP_PRES_11");
        seedProject("PRJ_11", "P2");
        seedProject("PRJ_12", "P2");
        seedPeriod("PERIOD_R11", "CALIBRATING");
        seedAssignment("PRJ_11", "P2", "PRESIDENT", "EMP_PRES_11", true);
        seedAssignment("PRJ_12", "P2", "PRESIDENT", "EMP_PRES_11", true);
        Long idA = seedConfirmation("PERIOD_R11", "PRJ_11", "EMP_A", "PENDING", 0);
        Long idB = seedConfirmation("PERIOD_R11", "PRJ_11", "EMP_B", "PENDING", 0);
        Long idOther = seedConfirmation("PERIOD_R11", "PRJ_12", "EMP_C", "PENDING", 0);

        auth("pres11", "总裁");

        presidentService.returnProject(idA, "员工A结果有误");

        assertEquals("RETURNED", projectConfirmationMapper.selectById(idA).getStatus());
        assertEquals("PENDING", projectConfirmationMapper.selectById(idB).getStatus(), "同项目其他人不应被退回");
        assertEquals("PENDING", projectConfirmationMapper.selectById(idOther).getStatus(), "其它项目不应被退回");
        assertEquals("CALIBRATING", periodMapper.selectById("PERIOD_R11").getStatus());
    }

    // 功能：退回后清空周期校准提交时间戳——周期回到待校准，PD 可重新提交（问题2）
    @Test
    void returnProjectShouldClearCalibrationSubmittedAt() {
        seedEmployee("EMP_PRES_12");
        seedUser("U_PRES_12", "pres12", "EMP_PRES_12");
        seedProject("PRJ_13", "P2");
        seedPeriod("PERIOD_R12", "CALIBRATING");
        seedAssignment("PRJ_13", "P2", "PRESIDENT", "EMP_PRES_12", true);
        Long id = seedConfirmation("PERIOD_R12", "PRJ_13", "EMP_D", "PENDING", 0);
        AssessmentPeriod p = periodMapper.selectById("PERIOD_R12");
        p.setCalibrationSubmittedAt(LocalDateTime.now());
        periodMapper.updateById(p);

        auth("pres12", "总裁");

        presidentService.returnProject(id, "结果有误");

        assertNull(periodMapper.selectById("PERIOD_R12").getCalibrationSubmittedAt(),
                "退回后 calibration_submitted_at 应被清空");
    }

    // 功能：总裁退回某项目只清该项目的校准提交记录，其它 PD 的已提交项目不受影响（项目级粒度）
    @Test
    void returnProjectShouldOnlyClearThatProjectsSubmission() {
        seedEmployee("EMP_PRES_RX");
        seedEmployee("EMP_A");
        seedEmployee("EMP_B");
        seedUser("U_PRES_RX", "pres_rx", "EMP_PRES_RX");
        seedProject("PRJ_RX_A", "P2");
        seedProject("PRJ_RX_B", "P2");
        seedPeriod("PERIOD_RX", "CALIBRATING");
        seedAssignment("PRJ_RX_A", "P2", "PRESIDENT", "EMP_PRES_RX", true);
        // 两个项目各自已提交（calibration_submission submitted_at 非空）
        seedSubmission("PERIOD_RX", "PRJ_RX_A", "P2", "EMP_PD_RX_A");
        seedSubmission("PERIOD_RX", "PRJ_RX_B", "P2", "EMP_PD_RX_B");
        // 涉及项目任务，使 refreshCalibrationSubmittedAt 的 involved 集合非空
        seedTask("PERIOD_RX", "EMP_A", "PRJ_RX_A", "P2");
        seedTask("PERIOD_RX", "EMP_B", "PRJ_RX_B", "P2");
        // 周期级时间戳置非空（模拟「全部提交」）
        AssessmentPeriod p = periodMapper.selectById("PERIOD_RX");
        p.setCalibrationSubmittedAt(LocalDateTime.now());
        periodMapper.updateById(p);

        Long id = seedConfirmation("PERIOD_RX", "PRJ_RX_A", "EMP_A", "PENDING", 0);

        auth("pres_rx", "总裁");
        presidentService.returnProject(id, "结果有误");

        CalibrationSubmission subA = calibrationSubmissionMapper.selectOne(
                new LambdaQueryWrapper<CalibrationSubmission>()
                        .eq(CalibrationSubmission::getPeriodId, "PERIOD_RX")
                        .eq(CalibrationSubmission::getProjectCode, "PRJ_RX_A"));
        CalibrationSubmission subB = calibrationSubmissionMapper.selectOne(
                new LambdaQueryWrapper<CalibrationSubmission>()
                        .eq(CalibrationSubmission::getPeriodId, "PERIOD_RX")
                        .eq(CalibrationSubmission::getProjectCode, "PRJ_RX_B"));
        assertNull(subA.getSubmittedAt(), "被退回项目的提交记录应被清空");
        assertNotNull(subB.getSubmittedAt(), "其它项目的提交记录不应受影响");
        assertNull(periodMapper.selectById("PERIOD_RX").getCalibrationSubmittedAt(),
                "仍有项目未提交，周期级时间戳应清空");
    }

    // 功能：全部项目确认通过后周期原子翻 CONFIRMED——最后一项通过前仍 CALIBRATING
    @Test
    void approveAllShouldFlipPeriodToConfirmed() {
        seedEmployee("EMP_PRES_2");
        seedUser("U_PRES_2", "pres2", "EMP_PRES_2");
        seedProject("PRJ_3", "P2");
        seedProject("PRJ_4", "P2");
        seedPeriod("PERIOD_A2", "CALIBRATING");
        seedAssignment("PRJ_3", "P2", "PRESIDENT", "EMP_PRES_2", true);
        seedAssignment("PRJ_4", "P2", "PRESIDENT", "EMP_PRES_2", true);
        Long id1 = seedConfirmation("PERIOD_A2", "PRJ_3", "PENDING", 0);
        Long id2 = seedConfirmation("PERIOD_A2", "PRJ_4", "PENDING", 0);

        auth("pres2", "总裁");

        presidentService.approve(id1);
        assertEquals("CALIBRATING", periodMapper.selectById("PERIOD_A2").getStatus(), "仍有待确认项，不应翻转");

        presidentService.approve(id2);
        assertEquals("CONFIRMED", periodMapper.selectById("PERIOD_A2").getStatus());
        assertEquals("APPROVED", projectConfirmationMapper.selectById(id1).getStatus());
        assertEquals("APPROVED", projectConfirmationMapper.selectById(id2).getStatus());
    }

    // 功能：退回次数达 PRESIDENT_RETURN_TIMES 上限时拒绝——return_count=3 再退回报 400
    @Test
    void returnProjectShouldRejectWhenAtCap() {
        seedEmployee("EMP_PRES_3");
        seedUser("U_PRES_3", "pres3", "EMP_PRES_3");
        seedProject("PRJ_5", "P2");
        seedPeriod("PERIOD_R3", "CALIBRATING");
        seedAssignment("PRJ_5", "P2", "PRESIDENT", "EMP_PRES_3", true);
        Long id = seedConfirmation("PERIOD_R3", "PRJ_5", "PENDING", 3); // 已达上限

        auth("pres3", "总裁");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> presidentService.returnProject(id, "再次退回"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("上限"));
    }

    // 功能：PD 重提交——RETURNED→PENDING，清除退回原因，保留退回计数（当前用户须为该项目主 PD）
    @Test
    void resubmitShouldResetReturnedToPending() {
        seedEmployee("EMP_PRES_4");
        seedEmployee("EMP_PD_4");
        seedUser("U_PRES_4", "pres4", "EMP_PRES_4");
        seedUser("U_PD_4", "pd_resubmit", "EMP_PD_4");
        seedRole("PD");
        seedProject("PRJ_6", "P2");
        seedPeriod("PERIOD_S4", "CALIBRATING");
        seedAssignment("PRJ_6", "P2", "PRESIDENT", "EMP_PRES_4", true);
        seedAssignment("PRJ_6", "P2", "PD", "EMP_PD_4", true);
        Long id = seedConfirmation("PERIOD_S4", "PRJ_6", "RETURNED", 1);
        ProjectConfirmation seeded = projectConfirmationMapper.selectById(id);
        seeded.setReturnReason("旧原因");
        projectConfirmationMapper.updateById(seeded);

        auth("pd_resubmit", "PD");

        presidentService.resubmit(id);

        ProjectConfirmation after = projectConfirmationMapper.selectById(id);
        assertEquals("PENDING", after.getStatus());
        assertNull(after.getReturnReason());
        assertEquals(1, after.getReturnCount());
    }

    // 功能：非该项目主 PD 重提交被拒——当前用户虽是 PD 角色，但非该项目主 PD 时 403
    @Test
    void resubmitShouldRejectNonPrimaryPd() {
        seedEmployee("EMP_PD_OWNER");
        seedEmployee("EMP_PD_OTHER");
        seedUser("U_PD_OWNER", "pd_owner", "EMP_PD_OWNER");
        seedUser("U_PD_OTHER", "pd_other", "EMP_PD_OTHER");
        seedRole("PD");
        seedProject("PRJ_10", "P2");
        seedPeriod("PERIOD_PD", "CALIBRATING");
        seedAssignment("PRJ_10", "P2", "PD", "EMP_PD_OWNER", true);
        Long id = seedConfirmation("PERIOD_PD", "PRJ_10", "RETURNED", 0);

        auth("pd_other", "PD");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> presidentService.resubmit(id));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("负责 PD"));
    }

    // 功能：确认清单仅返回当前主总裁的项目——另一总裁的项目不泄露
    @Test
    void listConfirmationsShouldOnlyReturnOwnProjects() {
        seedEmployee("EMP_PRES_5");
        seedEmployee("EMP_PRES_OTHER");
        seedUser("U_PRES_5", "pres5", "EMP_PRES_5");
        seedProject("PRJ_7", "P2");
        seedProject("PRJ_8", "P2");
        seedPeriod("PERIOD_L5", "CALIBRATING");
        seedAssignment("PRJ_7", "P2", "PRESIDENT", "EMP_PRES_5", true);
        seedAssignment("PRJ_8", "P2", "PRESIDENT", "EMP_PRES_OTHER", true);
        seedConfirmation("PERIOD_L5", "PRJ_7", "PENDING", 0);
        seedConfirmation("PERIOD_L5", "PRJ_8", "PENDING", 0);

        auth("pres5", "总裁");

        List<PresidentService.ConfirmationItem> items = presidentService.listConfirmations("PERIOD_L5");

        assertEquals(1, items.size());
        assertEquals("PRJ_7", items.get(0).projectCode());
    }

    // 功能：确认清单返回周期名——periodName 从 assessment_period 批量回填，供前端周期列展示
    @Test
    void listConfirmationsShouldReturnPeriodName() {
        seedEmployee("EMP_PN_1");
        seedUser("U_PN_1", "pres_pn", "EMP_PN_1");
        seedProject("PRJ_PN", "P2");
        seedPeriod("PERIOD_PN", "CALIBRATING");
        seedAssignment("PRJ_PN", "P2", "PRESIDENT", "EMP_PN_1", true);
        seedConfirmation("PERIOD_PN", "PRJ_PN", "PENDING", 0);

        auth("pres_pn", "总裁");

        List<PresidentService.ConfirmationItem> items = presidentService.listConfirmations("PERIOD_PN");

        assertEquals(1, items.size());
        assertEquals("周期PERIOD_PN", items.get(0).periodName());
    }

    // 功能：非该项目的负责总裁被 403——虽为总裁角色，但非主总裁无权操作
    @Test
    void nonPresidentShouldBeForbidden() {
        seedEmployee("EMP_PRES_6");
        seedEmployee("EMP_OTHER_6");
        seedUser("U_PRES_6", "pres6", "EMP_PRES_6");
        seedUser("U_OTHER_6", "other6", "EMP_OTHER_6");
        seedProject("PRJ_9", "P2");
        seedPeriod("PERIOD_F6", "CALIBRATING");
        seedAssignment("PRJ_9", "P2", "PRESIDENT", "EMP_PRES_6", true);
        Long id = seedConfirmation("PERIOD_F6", "PRJ_9", "PENDING", 0);

        auth("other6", "总裁");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> presidentService.approve(id));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("负责总裁"));
    }

    // 功能：多阶段主总裁一致时返回该工号（一项目一主总裁）
    @Test
    void resolvePrimaryPresidentShouldReturnSingleWhenConsistentAcrossStages() {
        seedEmployee("EMP_CP_1");
        seedProject("PRJ_CP_A", "P1");
        seedProject("PRJ_CP_A", "P2");
        seedAssignment("PRJ_CP_A", "P1", "PRESIDENT", "EMP_CP_1", true);
        seedAssignment("PRJ_CP_A", "P2", "PRESIDENT", "EMP_CP_1", true);

        assertEquals("EMP_CP_1", presidentService.resolvePrimaryPresident("PRJ_CP_A"));
    }

    // 功能：多阶段主总裁不一致时返回 null（跨阶段分配冲突，不静默取首个）
    @Test
    void resolvePrimaryPresidentShouldReturnNullWhenInconsistentAcrossStages() {
        seedEmployee("EMP_CP_2");
        seedEmployee("EMP_CP_3");
        seedProject("PRJ_CP_B", "P1");
        seedProject("PRJ_CP_B", "P2");
        seedAssignment("PRJ_CP_B", "P1", "PRESIDENT", "EMP_CP_2", true);
        seedAssignment("PRJ_CP_B", "P2", "PRESIDENT", "EMP_CP_3", true);

        assertNull(presidentService.resolvePrimaryPresident("PRJ_CP_B"));
    }

    // 功能：确认清单标记主总裁冲突——跨阶段多个主总裁时 presidentConflict=true
    @Test
    void listConfirmationsShouldFlagPresidentConflict() {
        seedEmployee("EMP_CF_A");
        seedEmployee("EMP_CF_B");
        seedUser("U_CF_A", "conf_a", "EMP_CF_A");
        seedProject("PRJ_CF", "P1");
        seedProject("PRJ_CF", "P2");
        seedPeriod("PERIOD_CF", "CALIBRATING");
        seedAssignment("PRJ_CF", "P1", "PRESIDENT", "EMP_CF_A", true);
        seedAssignment("PRJ_CF", "P2", "PRESIDENT", "EMP_CF_B", true);
        seedConfirmation("PERIOD_CF", "PRJ_CF", "PENDING", 0);

        auth("conf_a", "总裁");

        List<PresidentService.ConfirmationItem> items = presidentService.listConfirmations("PERIOD_CF");

        assertEquals(1, items.size());
        assertTrue(items.get(0).presidentConflict());
    }

    // 辅助：插入项目角色（满足 project_role_assignment 的外键 fk_pra_role）
    private void seedRole(String roleCode) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode(roleCode);
        role.setRoleName("角色" + roleCode);
        projectRoleMapper.insert(role);
    }

    // 辅助：插入员工
    private void seedEmployee(String employeeId) {
        Employee emp = new Employee();
        emp.setEmployeeId(employeeId);
        emp.setName("员工" + employeeId);
        emp.setEmail(employeeId + "@test.com");
        emp.setCategory("管理类");
        emp.setPosition("项目经理");
        emp.setOrgName("信息部");
        emp.setStatus("ACTIVE");
        employeeMapper.insert(emp);
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

    // 辅助：插入项目（复合主键 project_code+project_stage）
    private void seedProject(String code, String stage) {
        Project project = new Project();
        project.setProjectCode(code);
        project.setProjectName("项目" + code);
        project.setProjectStage(stage);
        project.setStatus("ACTIVE");
        project.setStageConfirmed(false);
        projectMapper.insert(project);
    }

    // 辅助：插入指定状态的考核周期
    private void seedPeriod(String periodId, String status) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(periodId);
        period.setPeriodName("周期" + periodId);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 12, 31));
        period.setStatus(status);
        periodMapper.insert(period);
    }

    // 辅助：插入角色分配（PRESIDENT 角色已在 V27 种子，无需重复插 project_role）
    private void seedAssignment(String projectCode, String stage, String roleCode, String employeeId, boolean isPrimary) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode(roleCode);
        a.setEmployeeId(employeeId);
        a.setIsPrimary(isPrimary);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：插入项目确认行（assesseeId 缺省取 projectCode），返回自增主键
    private Long seedConfirmation(String periodId, String projectCode, String status, int returnCount) {
        return seedConfirmation(periodId, projectCode, projectCode, status, returnCount);
    }

    // 辅助：插入项目确认行（显式 assesseeId，用于同一项目多人确认场景），返回自增主键
    private Long seedConfirmation(String periodId, String projectCode, String assesseeId, String status, int returnCount) {
        ProjectConfirmation c = new ProjectConfirmation();
        c.setPeriodId(periodId);
        c.setProjectCode(projectCode);
        c.setAssesseeId(assesseeId);
        c.setStatus(status);
        c.setReturnCount(returnCount);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        projectConfirmationMapper.insert(c);
        return c.getId();
    }

    // 辅助：插入项目级校准提交记录（submitted_at 非空）
    private void seedSubmission(String periodId, String code, String stage, String employeeId) {
        CalibrationSubmission s = new CalibrationSubmission();
        s.setPeriodId(periodId);
        s.setProjectCode(code);
        s.setProjectStage(stage);
        s.setSubmittedByEmployeeId(employeeId);
        s.setSubmittedAt(LocalDateTime.now());
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        calibrationSubmissionMapper.insert(s);
    }

    // 辅助：插入 SUBMITTED 项目任务（评估人=被考核人，满足 fk_task_assessor/fk_task_assessee）
    private void seedTask(String periodId, String assesseeId, String projectCode, String projectStage) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(periodId);
        task.setAssessorId(assesseeId);
        task.setAssesseeId(assesseeId);
        task.setProjectCode(projectCode);
        task.setProjectStage(projectStage);
        task.setTaskType("PROJECT");
        task.setStatus("SUBMITTED");
        taskMapper.insert(task);
    }

    // 辅助：设置当前登录用户及其角色
    private void auth(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
