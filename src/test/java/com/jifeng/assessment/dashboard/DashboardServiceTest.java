// 模块用途：DashboardService 单元测试——覆盖空库统计、部分数据统计、差异报告
// 依赖文件：DashboardService.java, EmployeeMapper.java, ProjectRoleMapper.java, ProjectMapper.java, PositionConfigMapper.java, ProjectKpiMapper.java, FuncKpiMapper.java
// 修改注意：用现有Mapper写入测试数据，每个测试独立回滚
package com.jifeng.assessment.dashboard;

import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigMapper;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DashboardServiceTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired
    private ProjectRoleMapper projectRoleMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private PositionConfigMapper positionConfigMapper;
    @Autowired
    private ProjectKpiMapper projectKpiMapper;
    @Autowired
    private FuncKpiMapper funcKpiMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private PeriodMapper periodMapper;
    @Autowired
    private ProjectRoleAssignmentMapper roleAssignmentMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private ProjectConfirmationMapper projectConfirmationMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // 功能：仅种子数据时——admin员工 + PRESIDENT角色已配置，其余模块为待配置
    @Test
    void shouldShowOnlySeedDataWhenNoOtherData() {
        // DataInitializer 种子 1 admin 员工；V27 迁移种子 1 个 PRESIDENT 项目角色
        List<DashboardService.ConfigProgressItem> items = dashboardService.configProgress();
        assertEquals(5, items.size());
        assertEquals(1, getCount(items, "employee"), "seed admin employee");
        assertEquals(DashboardService.STATUS_CONFIGURED, getStatus(items, "employee"));
        assertEquals(1, getCount(items, "projectRole"), "seed PRESIDENT role");
        assertEquals(DashboardService.STATUS_CONFIGURED, getStatus(items, "projectRole"));
        assertEquals(0, getCount(items, "project"));
        assertEquals(DashboardService.STATUS_PENDING, getStatus(items, "project"));
        assertEquals(0, getCount(items, "positionConfig"));
        assertEquals(DashboardService.STATUS_PENDING, getStatus(items, "positionConfig"));
        assertEquals(0, getCount(items, "kpi"));
        assertEquals(DashboardService.STATUS_PENDING, getStatus(items, "kpi"));
    }

    // 功能：有数据时count反映实际数量且status="已配置"
    @Test
    void shouldReturnCorrectCountsWithData() {
        Employee emp = new Employee();
        emp.setEmployeeId("DASH001");
        emp.setName("测试");
        emp.setEmail("dash@test.com");
        emp.setCategory("研发");
        emp.setPosition("工程师");
        emp.setOrgName("研发部");
        emp.setStatus("ACTIVE");
        employeeMapper.insert(emp);

        ProjectRole role = new ProjectRole();
        role.setRoleCode("DASH_ROLE");
        role.setRoleName("测试角色");
        role.setIsActive(true);
        projectRoleMapper.insert(role);

        Project proj = new Project();
        proj.setProjectCode("DASH_PROJ");
        proj.setProjectName("测试项目");
        proj.setProjectStage("P2");
        proj.setStatus("ACTIVE");
        projectMapper.insert(proj);

        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory("研发");
        config.setPosition("工程师");
        config.setIsProjectBased(true);
        config.setDefaultProjectRole("DASH_ROLE");
        config.setFuncAssessMode("SINGLE");
        config.setProjectWeight(new BigDecimal("0.7"));
        config.setFuncWeight(new BigDecimal("0.3"));
        positionConfigMapper.insert(config);

        ProjectKpiConfig pkpi = new ProjectKpiConfig();
        pkpi.setProjectRoleCode("DASH_ROLE");
        pkpi.setProjectStage("P2");
        pkpi.setKpiName("测试KPI");
        pkpi.setEvaluationCriteria("标准");
        pkpi.setWeight(new BigDecimal("1.0"));
        pkpi.setSortOrder(1);
        pkpi.setIsActive(true);
        projectKpiMapper.insert(pkpi);

        List<DashboardService.ConfigProgressItem> items = dashboardService.configProgress();

        // 1 seed admin + 1 test employee = 2
        assertEquals(2, getCount(items, "employee"));
        // 1 seed PRESIDENT + 1 test DASH_ROLE = 2
        assertEquals(2, getCount(items, "projectRole"));
        assertEquals(1, getCount(items, "project"));
        assertEquals(1, getCount(items, "positionConfig"));
        assertEquals(1, getCount(items, "kpi"));

        for (DashboardService.ConfigProgressItem item : items) {
            assertEquals(DashboardService.STATUS_CONFIGURED, item.status(), item.key() + " should be " + DashboardService.STATUS_CONFIGURED);
        }
    }

    // 功能：部分模块有数据时，仅相应项为"已配置"
    @Test
    void shouldShowPendingForEmptyModules() {
        Employee emp = new Employee();
        emp.setEmployeeId("DASH002");
        emp.setName("部分配置");
        emp.setEmail("partial@test.com");
        emp.setCategory("研发");
        emp.setPosition("工程师");
        emp.setOrgName("研发部");
        emp.setStatus("ACTIVE");
        employeeMapper.insert(emp);

        List<DashboardService.ConfigProgressItem> items = dashboardService.configProgress();

        // 1 seed admin + 1 test employee = 2
        assertEquals(2, getCount(items, "employee"));
        assertEquals(DashboardService.STATUS_CONFIGURED, getStatus(items, "employee"));
        assertEquals(1, getCount(items, "projectRole"), "seed PRESIDENT role");
        assertEquals(DashboardService.STATUS_CONFIGURED, getStatus(items, "projectRole"));
        assertEquals(0, getCount(items, "project"));
        assertEquals(DashboardService.STATUS_PENDING, getStatus(items, "project"));
    }

    // 功能：diffReport在阶段1返回空列表
    // 阶段2：补充非空diffReport测试——包含缺岗位配置的员工、无考核人的员工、缺少上级等异常项
    @Test
    void shouldReturnEmptyDiffReport() {
        List<String> report = dashboardService.diffReport();
        assertNotNull(report);
        assertTrue(report.isEmpty());
    }

    // 功能：KPI计数=项目KPI+职能KPI
    @Test
    void shouldSumProjectAndFuncKpiCounts() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("KPIROLE");
        role.setRoleName("KPI角色");
        role.setIsActive(true);
        projectRoleMapper.insert(role);

        ProjectKpiConfig pkpi = new ProjectKpiConfig();
        pkpi.setProjectRoleCode("KPIROLE");
        pkpi.setProjectStage("P2");
        pkpi.setKpiName("项目KPI");
        pkpi.setEvaluationCriteria("标准");
        pkpi.setWeight(new BigDecimal("1.0"));
        pkpi.setSortOrder(1);
        pkpi.setIsActive(true);
        projectKpiMapper.insert(pkpi);

        FuncKpiConfig fkpi = new FuncKpiConfig();
        fkpi.setCategory("研发");
        fkpi.setPosition("工程师");
        fkpi.setKpiName("职能KPI");
        fkpi.setEvaluationCriteria("标准");
        fkpi.setWeight(new BigDecimal("1.0"));
        fkpi.setSortOrder(1);
        fkpi.setIsActive(true);
        funcKpiMapper.insert(fkpi);

        List<DashboardService.ConfigProgressItem> items = dashboardService.configProgress();
        assertEquals(2, getCount(items, "kpi"), "KPI count should sum project + func KPIs");
    }

    // 功能：PD 待处理——CALIBRATING 且未提交校准的周期中，我为主 PD 的周期数
    @Test
    void pdPendingCountShouldCountUnsubmittedCalibrationPeriods() {
        seedEmployee("EMP_PD_DASH");
        seedUser("U_PD_DASH", "pd_dash", "EMP_PD_DASH");
        seedRole("PD");
        seedProject("PRJ_PD_DASH", "P2");
        seedPeriod("PERIOD_PD_DASH", "CALIBRATING"); // calibration_submitted_at = NULL
        seedAssignment("PRJ_PD_DASH", "P2", "PD", "EMP_PD_DASH", true);
        seedTask("PERIOD_PD_DASH", "PRJ_PD_DASH", "EMP_PD_DASH");

        auth("pd_dash", "PD");

        assertEquals(1, dashboardService.pendingCount());
    }

    // 功能：总裁待处理——CALIBRATING 且已提交校准、我为主总裁的项目中 PENDING 确认人数
    @Test
    void presidentPendingCountShouldCountPendingConfirmations() {
        seedEmployee("EMP_PRES_DASH");
        seedUser("U_PRES_DASH", "pres_dash", "EMP_PRES_DASH");
        seedProject("PRJ_PRES_DASH", "P2");
        AssessmentPeriod period = seedPeriod("PERIOD_PRES_DASH", "CALIBRATING");
        period.setCalibrationSubmittedAt(LocalDateTime.now());
        periodMapper.updateById(period); // 已提交校准
        seedAssignment("PRJ_PRES_DASH", "P2", "PRESIDENT", "EMP_PRES_DASH", true);
        seedConfirmation("PERIOD_PRES_DASH", "PRJ_PRES_DASH", "EMP_A", "PENDING");

        auth("pres_dash", "总裁");

        assertEquals(1, dashboardService.pendingCount());
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

    // 辅助：插入项目角色
    private void seedRole(String roleCode) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode(roleCode);
        role.setRoleName("角色" + roleCode);
        role.setIsActive(true);
        projectRoleMapper.insert(role);
    }

    // 辅助：插入项目
    private void seedProject(String code, String stage) {
        Project project = new Project();
        project.setProjectCode(code);
        project.setProjectName("项目" + code);
        project.setProjectStage(stage);
        project.setStatus("ACTIVE");
        project.setStageConfirmed(false);
        projectMapper.insert(project);
    }

    // 辅助：插入考核周期
    private AssessmentPeriod seedPeriod(String periodId, String status) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(periodId);
        period.setPeriodName("周期" + periodId);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 12, 31));
        period.setStatus(status);
        periodMapper.insert(period);
        return period;
    }

    // 辅助：插入角色分配
    private void seedAssignment(String projectCode, String stage, String roleCode, String employeeId, boolean isPrimary) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode(roleCode);
        a.setEmployeeId(employeeId);
        a.setIsPrimary(isPrimary);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：插入项目任务（PROJECT 类型）
    private void seedTask(String periodId, String projectCode, String assesseeId) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(periodId);
        task.setAssessorId(assesseeId);
        task.setAssesseeId(assesseeId);
        task.setProjectCode(projectCode);
        task.setProjectStage("P2");
        task.setTaskType("PROJECT");
        task.setStatus("SUBMITTED");
        taskMapper.insert(task);
    }

    // 辅助：插入项目确认行
    private void seedConfirmation(String periodId, String projectCode, String assesseeId, String status) {
        ProjectConfirmation c = new ProjectConfirmation();
        c.setPeriodId(periodId);
        c.setProjectCode(projectCode);
        c.setAssesseeId(assesseeId);
        c.setStatus(status);
        c.setReturnCount(0);
        projectConfirmationMapper.insert(c);
    }

    // 辅助：设置当前登录用户及其角色
    private void auth(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private long getCount(List<DashboardService.ConfigProgressItem> items, String key) {
        return items.stream()
                .filter(i -> i.key().equals(key))
                .findFirst()
                .map(DashboardService.ConfigProgressItem::count)
                .orElse(-1L);
    }

    private String getStatus(List<DashboardService.ConfigProgressItem> items, String key) {
        return items.stream()
                .filter(i -> i.key().equals(key))
                .findFirst()
                .map(DashboardService.ConfigProgressItem::status)
                .orElse(null);
    }
}
