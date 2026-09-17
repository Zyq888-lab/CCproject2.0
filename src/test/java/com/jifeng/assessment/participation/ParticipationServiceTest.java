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

    // 辅助：插入考核周期（INIT 状态，不触发审批后的任务生成）
    private void seedPeriod(String periodId) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(periodId);
        period.setPeriodName("周期" + periodId);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 12, 31));
        period.setStatus("INIT");
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
