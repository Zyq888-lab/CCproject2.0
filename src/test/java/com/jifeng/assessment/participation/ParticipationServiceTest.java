// 模块用途：ParticipationService 审批权限单元测试——覆盖主PM/非主PM/PD/ADMIN/跨阶段五种审批场景
// 依赖文件：ParticipationService.java, EmployeeProjectParticipation.java, ProjectRoleAssignment.java
// 修改注意：测试用 H2 内存库，每个用例独立，不依赖执行顺序；参与记录关联 project/employee/assessment_period 外键
package com.jifeng.assessment.participation;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ParticipationServiceTest {

    @Autowired
    private ParticipationService participationService;

    @Autowired
    private ParticipationMapper participationMapper;

    @Autowired
    private ProjectRoleMapper projectRoleMapper;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private ProjectRoleAssignmentMapper roleAssignmentMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private PeriodMapper periodMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // 功能：主 PM 审批通过——project_role_code='PM' AND is_primary=true AND project_stage=参与记录阶段，状态置为 APPROVED
    @Test
    void primaryPmShouldApprove() {
        seedRole("PM");
        seedEmployee("EMP_PART_1");
        seedEmployee("EMP_PM_1");
        seedUser("U_PM_1", "pm_primary", "EMP_PM_1");
        seedProject("PRJ_A", "P2");
        seedPeriod("PERIOD_1");
        seedAssignment("PRJ_A", "P2", "PM", "EMP_PM_1", true);
        Long id = seedParticipation("EMP_PART_1", "PERIOD_1", "PRJ_A", "P2");

        auth("pm_primary", "PM");

        EmployeeProjectParticipation result = participationService.approve(id, true, null, "同意");

        assertEquals("APPROVED", result.getStatus());
        assertEquals("pm_primary", result.getApprovedBy());
    }

    // 功能：非主 PM 被 403——该 PM 在项目上 is_primary=false，无权审批
    @Test
    void nonPrimaryPmShouldBeForbidden() {
        seedRole("PM");
        seedEmployee("EMP_PART_2");
        seedEmployee("EMP_PM_2");
        seedUser("U_PM_2", "pm_secondary", "EMP_PM_2");
        seedProject("PRJ_B", "P2");
        seedPeriod("PERIOD_2");
        seedAssignment("PRJ_B", "P2", "PM", "EMP_PM_2", false);
        Long id = seedParticipation("EMP_PART_2", "PERIOD_2", "PRJ_B", "P2");

        auth("pm_secondary", "PM");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.approve(id, true, null, null));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("主 PM"));
    }

    // 功能：PD 被 403——即使为主 PD（is_primary=true AND role='PD'），也不具备审批资格
    @Test
    void pdShouldBeForbidden() {
        seedRole("PD");
        seedEmployee("EMP_PART_3");
        seedEmployee("EMP_PD_1");
        seedUser("U_PD_1", "pd_primary", "EMP_PD_1");
        seedProject("PRJ_C", "P2");
        seedPeriod("PERIOD_3");
        seedAssignment("PRJ_C", "P2", "PD", "EMP_PD_1", true);
        Long id = seedParticipation("EMP_PART_3", "PERIOD_3", "PRJ_C", "P2");

        auth("pd_primary", "PD");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.approve(id, true, null, null));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("主 PM"));
    }

    // 功能：ADMIN 放行——不受主 PM 校验约束，可直接审批通过
    @Test
    void adminShouldApprove() {
        seedRole("PM");
        seedEmployee("EMP_PART_4");
        seedEmployee("EMP_ADMIN_1");
        seedUser("U_ADMIN_1", "admin_approve", "EMP_ADMIN_1");
        seedProject("PRJ_D", "P2");
        seedPeriod("PERIOD_4");
        Long id = seedParticipation("EMP_PART_4", "PERIOD_4", "PRJ_D", "P2");

        auth("admin_approve", "ADMIN");

        EmployeeProjectParticipation result = participationService.approve(id, true, null, null);

        assertEquals("APPROVED", result.getStatus());
    }

    // 功能：跨阶段被 403——PM 是项目 P2 阶段的主 PM，但参与记录属于 P3 阶段，无权审批
    @Test
    void crossStageShouldBeForbidden() {
        seedRole("PM");
        seedEmployee("EMP_PART_5");
        seedEmployee("EMP_PM_5");
        seedUser("U_PM_5", "pm_cross", "EMP_PM_5");
        seedProject("PRJ_E", "P2");
        seedProject("PRJ_E", "P3");
        seedPeriod("PERIOD_5");
        seedAssignment("PRJ_E", "P2", "PM", "EMP_PM_5", true);
        Long id = seedParticipation("EMP_PART_5", "PERIOD_5", "PRJ_E", "P3");

        auth("pm_cross", "PM");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.approve(id, true, null, null));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("主 PM"));
    }

    // 功能：列表返回当前审批人——PENDING 记录填充该项目该阶段主 PM 的工号（project_role_code='PM' AND is_primary=true）
    @Test
    void listShouldReturnCurrentApproverForPending() {
        seedRole("PM");
        seedEmployee("EMP_PART_9");
        seedEmployee("EMP_PM_9");
        seedEmployee("EMP_ADMIN_9");
        seedUser("U_ADMIN_9", "admin_list_9", "EMP_ADMIN_9");
        seedProject("PRJ_F", "P2");
        seedPeriod("PERIOD_9");
        seedAssignment("PRJ_F", "P2", "PM", "EMP_PM_9", true);
        Long id = seedParticipation("EMP_PART_9", "PERIOD_9", "PRJ_F", "P2");

        auth("admin_list_9", "ADMIN");

        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_9", null, null);

        EmployeeProjectParticipation row = result.getList().stream()
                .filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
        assertEquals("EMP_PM_9", row.getCurrentApproverEmployeeId());
    }

    // 功能：CALIBRATING 期拒绝填写参与——assertParticipatable 冻结校准/确认/关闭期参与写操作
    @Test
    void createShouldBeRejectedWhenCalibrating() {
        seedPeriodWithStatus("PERIOD_10", "CALIBRATING");
        auth("admin_cal", "ADMIN");

        ProjectParticipationItem item = new ProjectParticipationItem();
        item.setProjectCode("PRJ_X");
        item.setProjectStage("P2");
        item.setParticipationRate(new BigDecimal("100"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.create("EMP_X", "PERIOD_10", List.of(item)));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校准"));
    }

    // 功能：CALIBRATING 期拒绝审批——ADMIN 亦不可在校准期审批参与记录
    @Test
    void approveShouldBeRejectedWhenCalibrating() {
        seedEmployee("EMP_PART_10");
        seedProject("PRJ_X", "P2");
        seedPeriodWithStatus("PERIOD_10", "CALIBRATING");
        Long id = seedParticipation("EMP_PART_10", "PERIOD_10", "PRJ_X", "P2");

        auth("admin_cal", "ADMIN");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.approve(id, true, null, null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校准"));
    }

    // 功能：CALIBRATING 期拒绝重新提交——已拒绝记录在校准期不可重置为 PENDING
    @Test
    void resubmitShouldBeRejectedWhenCalibrating() {
        seedEmployee("EMP_PART_11");
        seedProject("PRJ_Y", "P2");
        seedPeriodWithStatus("PERIOD_11", "CALIBRATING");
        Long id = seedParticipation("EMP_PART_11", "PERIOD_11", "PRJ_Y", "P2");

        auth("admin_cal", "ADMIN");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.resubmit(id, new BigDecimal("80")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校准"));
    }

    // 功能：INIT 期允许填写参与——4a 曾误收紧为 ONGOING-only，回归验证「新建周期后即可填参与」
    @Test
    void createShouldSucceedWhenInit() {
        seedRole("PM");
        seedEmployee("EMP_INIT_1");
        seedUser("U_INIT_1", "emp_init_1", "EMP_INIT_1");
        seedProject("PRJ_I", "P2");
        seedPeriodWithStatus("PERIOD_INIT_1", "INIT");
        seedAssignment("PRJ_I", "P2", "PM", "EMP_INIT_1", false);

        auth("emp_init_1", "员工");

        ProjectParticipationItem item = new ProjectParticipationItem();
        item.setProjectCode("PRJ_I");
        item.setProjectStage("P2");
        item.setParticipationRate(new BigDecimal("100"));

        List<EmployeeProjectParticipation> result =
                participationService.create(null, "PERIOD_INIT_1", List.of(item));

        assertEquals(1, result.size());
        assertEquals("PENDING", result.get(0).getStatus());
    }

    // 功能：INIT 期允许审批参与——审批通过后不立即生成任务，交由 launch 统一生成
    @Test
    void approveShouldSucceedWhenInit() {
        seedRole("PM");
        seedEmployee("EMP_INIT_2");
        seedEmployee("EMP_PM_INIT");
        seedUser("U_INIT_2", "emp_init_2", "EMP_INIT_2");
        seedUser("U_PM_INIT", "pm_init", "EMP_PM_INIT");
        seedProject("PRJ_J", "P2");
        seedPeriodWithStatus("PERIOD_INIT_2", "INIT");
        seedAssignment("PRJ_J", "P2", "PM", "EMP_PM_INIT", true);
        Long id = seedParticipation("EMP_INIT_2", "PERIOD_INIT_2", "PRJ_J", "P2");

        auth("pm_init", "PM");

        EmployeeProjectParticipation result = participationService.approve(id, true, null, null);
        assertEquals("APPROVED", result.getStatus());
    }

    // 功能：INIT 期允许重新提交——被拒参与在发起前可重置为 PENDING
    @Test
    void resubmitShouldSucceedWhenInit() {
        seedRole("PM");
        seedEmployee("EMP_INIT_3");
        seedUser("U_INIT_3", "emp_init_3", "EMP_INIT_3");
        seedProject("PRJ_K", "P2");
        seedPeriodWithStatus("PERIOD_INIT_3", "INIT");
        Long id = seedParticipation("EMP_INIT_3", "PERIOD_INIT_3", "PRJ_K", "P2");
        EmployeeProjectParticipation p = participationMapper.selectById(id);
        p.setStatus("REJECTED");
        participationMapper.updateById(p);

        auth("emp_init_3", "员工");

        EmployeeProjectParticipation result = participationService.resubmit(id, new BigDecimal("80"));
        assertEquals("PENDING", result.getStatus());
    }

    // 功能：多角色（员工+PM）用户取并集视角——既能看到自己作为员工提交的参与记录（非主负责项目），
    //   也能看到自己主负责项目上的他人参与记录（PM 审批视角），二者互不吞并
    @Test
    void employeeWithPmRoleShouldSeeOwnAndManagedParticipations() {
        seedRole("PM");
        seedEmployee("EMP_MULTI");
        seedEmployee("EMP_OTHER");
        seedUser("U_MULTI", "multi_user", "EMP_MULTI");
        seedProject("PRJ_OWN", "P2");
        seedProject("PRJ_MANAGED", "P2");
        seedPeriod("PERIOD_MULTI");
        seedAssignment("PRJ_MANAGED", "P2", "PM", "EMP_MULTI", true);
        Long ownId = seedParticipation("EMP_MULTI", "PERIOD_MULTI", "PRJ_OWN", "P2");
        Long managedId = seedParticipation("EMP_OTHER", "PERIOD_MULTI", "PRJ_MANAGED", "P2");

        // 同时授予「员工」与「PM」两个角色
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("multi_user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_员工"),
                                new SimpleGrantedAuthority("ROLE_PM"))));

        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_MULTI", null, null);

        List<Long> ids = result.getList().stream().map(EmployeeProjectParticipation::getId).toList();
        assertTrue(ids.contains(ownId), "员工+PM 用户应能看到自己作为员工提交的参与记录");
        assertTrue(ids.contains(managedId), "员工+PM 用户应能看到自己主负责项目上的他人参与记录");
    }

    // 功能：评估人不再授予项目可见性——仅持有「评估人」角色(无员工/PM/PD)时，即便在项目上有角色分配，
    //   也不应看到他人参与记录（旧逻辑会经 listAssignedProjectCodes 泄露该项目的全部参与记录）
    @Test
    void assessorShouldNotSeeOthersParticipations() {
        seedRole("PM");
        seedEmployee("EMP_ASSESSOR");
        seedEmployee("EMP_OTHER2");
        seedUser("U_ASSESSOR", "assessor_user", "EMP_ASSESSOR");
        seedProject("PRJ_ASSESS", "P2");
        seedPeriod("PERIOD_ASSESS");
        // 评估人在该项目上有角色分配（主 PM）——旧逻辑据此授予项目可见性，导致泄露他人记录
        seedAssignment("PRJ_ASSESS", "P2", "PM", "EMP_ASSESSOR", true);
        seedParticipation("EMP_OTHER2", "PERIOD_ASSESS", "PRJ_ASSESS", "P2");

        auth("assessor_user", "评估人");

        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_ASSESS", null, null);

        assertTrue(result.getList().isEmpty(), "仅评估人角色的用户不应看到他人参与记录");
    }

    // 辅助：插入项目角色
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

    // 辅助：插入考核周期（ONGOING 状态——approve/create/resubmit 在 INIT/ONGOING 均可；
    //   缺岗位配置使审批通过后的增量任务生成 no-op，故测试不会触发任务生成）
    private void seedPeriod(String periodId) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(periodId);
        period.setPeriodName("周期" + periodId);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 12, 31));
        period.setStatus("ONGOING");
        periodMapper.insert(period);
    }

    // 辅助：插入指定状态的考核周期——用于验证非 ONGOING 状态拒绝参与写操作
    private void seedPeriodWithStatus(String periodId, String status) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(periodId);
        period.setPeriodName("周期" + periodId);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 12, 31));
        period.setStatus(status);
        periodMapper.insert(period);
    }

    // 辅助：插入角色分配（显式指定是否为主）
    private void seedAssignment(String projectCode, String stage, String roleCode, String employeeId, boolean isPrimary) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode(roleCode);
        a.setEmployeeId(employeeId);
        a.setIsPrimary(isPrimary);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：插入参与记录（PENDING 状态），返回自增主键
    private Long seedParticipation(String employeeId, String periodId, String projectCode, String projectStage) {
        EmployeeProjectParticipation p = new EmployeeProjectParticipation();
        p.setEmployeeId(employeeId);
        p.setPeriodId(periodId);
        p.setProjectCode(projectCode);
        p.setProjectStage(projectStage);
        p.setParticipationRate(new BigDecimal("100"));
        p.setStatus("PENDING");
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        participationMapper.insert(p);
        return p.getId();
    }

    // 辅助：设置当前登录用户及其角色
    private void auth(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
