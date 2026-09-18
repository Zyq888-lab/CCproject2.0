// 模块用途：PresidentService 并发测试——两位总裁并发确认最后两个项目，周期只翻转一次（悲观锁串行化）
// 依赖文件：PresidentService.java, PeriodMapper.selectByIdForUpdate
// 修改注意：非 @Transactional（种子数据需先提交、并发线程各自开事务才可见）；@AfterEach 用 JDBC 物理清理
package com.jifeng.assessment.president;

import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class PresidentConcurrencyTest {

    private static final String PERIOD_ID = "PERIOD_CONC";
    private static final String PRJ1 = "PRJ_C1";
    private static final String PRJ2 = "PRJ_C2";

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
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        // 物理清理（子表先删，满足外键顺序）；PRESIDENT 项目角色由 V27 种子，不在此清理
        jdbcTemplate.update("DELETE FROM project_confirmation WHERE period_id = ?", PERIOD_ID);
        jdbcTemplate.update("DELETE FROM project_role_assignment WHERE project_code IN (?, ?)", PRJ1, PRJ2);
        jdbcTemplate.update("DELETE FROM project WHERE project_code IN (?, ?)", PRJ1, PRJ2);
        jdbcTemplate.update("DELETE FROM sys_user WHERE user_id IN (?, ?)", "U_CONC_1", "U_CONC_2");
        jdbcTemplate.update("DELETE FROM employee WHERE employee_id IN (?, ?)", "EMP_C1", "EMP_C2");
        jdbcTemplate.update("DELETE FROM assessment_period WHERE period_id = ?", PERIOD_ID);
    }

    // 功能：两位总裁并发 approve 各自负责的最后两项——周期最终 CONFIRMED，且只翻转一次
    @Test
    void concurrentApprovesShouldFlipPeriodOnce() throws Exception {
        seedEmployee("EMP_C1");
        seedEmployee("EMP_C2");
        seedUser("U_CONC_1", "pres_c1", "EMP_C1");
        seedUser("U_CONC_2", "pres_c2", "EMP_C2");
        seedProject(PRJ1, "P2");
        seedProject(PRJ2, "P2");
        seedPeriod(PERIOD_ID, "CALIBRATING");
        seedAssignment(PRJ1, "P2", "PRESIDENT", "EMP_C1", true);
        seedAssignment(PRJ2, "P2", "PRESIDENT", "EMP_C2", true);
        Long id1 = seedConfirmation(PERIOD_ID, PRJ1, "PENDING", 0);
        Long id2 = seedConfirmation(PERIOD_ID, PRJ2, "PENDING", 0);

        // 两个线程就绪后同时出发，各自以总裁身份 approve 自己负责的项目
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> {
                auth("pres_c1", "总裁");
                ready.countDown();
                start.await();
                presidentService.approve(id1);
                return null;
            }));
            futures.add(pool.submit(() -> {
                auth("pres_c2", "总裁");
                ready.countDown();
                start.await();
                presidentService.approve(id2);
                return null;
            }));
            ready.await(30, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals("CONFIRMED", periodMapper.selectById(PERIOD_ID).getStatus(),
                "最后两项并发确认后周期应翻转为 CONFIRMED");
        assertEquals("APPROVED", projectConfirmationMapper.selectById(id1).getStatus());
        assertEquals("APPROVED", projectConfirmationMapper.selectById(id2).getStatus());
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

    // 辅助：插入角色分配（PRESIDENT 角色已在 V27 种子）
    private void seedAssignment(String projectCode, String stage, String roleCode, String employeeId, boolean isPrimary) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode(roleCode);
        a.setEmployeeId(employeeId);
        a.setIsPrimary(isPrimary);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：插入项目确认行，返回自增主键
    private Long seedConfirmation(String periodId, String projectCode, String status, int returnCount) {
        ProjectConfirmation c = new ProjectConfirmation();
        c.setPeriodId(periodId);
        c.setProjectCode(projectCode);
        c.setStatus(status);
        c.setReturnCount(returnCount);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        projectConfirmationMapper.insert(c);
        return c.getId();
    }

    // 辅助：设置当前登录用户及其角色
    private void auth(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
