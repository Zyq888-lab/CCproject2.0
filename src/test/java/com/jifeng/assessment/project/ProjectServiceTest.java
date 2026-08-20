// 模块用途：ProjectService 单元测试——覆盖创建项目、确认阶段、乐观锁冲突、重置阶段
// 依赖文件：ProjectService.java, ProjectMapper.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProjectServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private ProjectRoleMapper projectRoleMapper;

    @Autowired
    private ProjectRoleAssignmentMapper roleAssignmentMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // 功能：创建项目成功，projectCode、projectStage正确返回，stageConfirmed默认为false
    @Test
    void shouldCreateProject() {
        Project project = new Project();
        project.setProjectCode("PJ001");
        project.setProjectName("测试项目一");
        project.setProjectStage("P3");

        ProjectDTO dto = projectService.createProject(project);
        assertNotNull(dto);
        assertEquals("PJ001", dto.getProjectCode());
        assertEquals("P3", dto.getProjectStage());
        assertFalse(dto.getStageConfirmed());
        assertEquals("ACTIVE", dto.getStatus());
    }

    // 功能：同一projectCode+projectStage重复时抛出409（联合主键），不同stage可创建
    @Test
    void shouldRejectDuplicateCodeStageCombo() {
        Project p1 = new Project();
        p1.setProjectCode("PJ_DUP");
        p1.setProjectName("重复项目");
        p1.setProjectStage("P2");
        projectService.createProject(p1);

        // 同编码不同阶段 — 应该成功
        Project p2 = new Project();
        p2.setProjectCode("PJ_DUP");
        p2.setProjectName("重复项目P3");
        p2.setProjectStage("P3");
        ProjectDTO dto2 = projectService.createProject(p2);
        assertNotNull(dto2);

        // 同编码同阶段 — 应该拒绝
        Project p3 = new Project();
        p3.setProjectCode("PJ_DUP");
        p3.setProjectName("重复项目二");
        p3.setProjectStage("P2");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.createProject(p3));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    // 功能：无效projectStage时抛出400异常
    @Test
    void shouldRejectInvalidStage() {
        Project project = new Project();
        project.setProjectCode("PJ_BAD");
        project.setProjectName("无效阶段项目");
        project.setProjectStage("P99");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.createProject(project));
        assertTrue(ex.getMessage().contains("无效的项目阶段"));
    }

    // 功能：PM确认阶段成功，confirmedBy记录当前用户，confirmedAt非空
    @Test
    void shouldConfirmStage() {
        Project project = new Project();
        project.setProjectCode("PJ_CONFIRM");
        project.setProjectName("阶段确认项目");
        project.setProjectStage("P4");
        projectService.createProject(project);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_zhang", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"))));

        ProjectDTO dto = projectService.confirmStage("PJ_CONFIRM", "P4");
        assertTrue(dto.getStageConfirmed());
        assertEquals("pm_zhang", dto.getConfirmedBy());
        assertNotNull(dto.getConfirmedAt());
    }

    // 功能：已确认阶段再次确认时抛出400异常
    @Test
    void shouldRejectDuplicateConfirm() {
        Project project = new Project();
        project.setProjectCode("PJ_CONF2");
        project.setProjectName("重复确认项目");
        project.setProjectStage("P3");
        projectService.createProject(project);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_li", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"))));

        projectService.confirmStage("PJ_CONF2", "P3");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.confirmStage("PJ_CONF2", "P3"));
        assertTrue(ex.getMessage().contains("已确认"));
    }

    // 功能：并发确认时乐观锁冲突返回409——模拟version不匹配场景
    @Test
    void shouldThrowOptimisticLockOnConflict() {
        Project project = new Project();
        project.setProjectCode("PJ_CONCUR");
        project.setProjectName("并发测试项目");
        project.setProjectStage("P5");
        projectService.createProject(project);

        // 模拟并发：通过selectByCodeAndStage读取并更新，推进version
        Project fresh = projectMapper.selectByCodeAndStage("PJ_CONCUR", "P5");
        fresh.setDescription("并发修改的内容");
        projectMapper.updateById(fresh); // version now 1

        // 构造一个version=0的过期实体，模拟并发窗口期
        Project stale = new Project();
        stale.setProjectCode("PJ_CONCUR");
        stale.setProjectStage("P5");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateWithOptimisticLock(stale));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被他人修改"));
    }

    // 功能：ADMIN重置阶段后stageConfirmed变false，confirmedBy和confirmedAt被清空
    @Test
    void shouldResetStage() {
        Project project = new Project();
        project.setProjectCode("PJ_RESET");
        project.setProjectName("重置阶段项目");
        project.setProjectStage("P2");
        projectService.createProject(project);

        // 先确认
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_wang", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"))));
        projectService.confirmStage("PJ_RESET", "P2");
        SecurityContextHolder.clearContext();

        // 重置
        ProjectDTO dto = projectService.resetStage("PJ_RESET", "P2");
        assertFalse(dto.getStageConfirmed());
        assertNull(dto.getConfirmedBy());
        assertNull(dto.getConfirmedAt());
    }

    // 功能：PM 数据隔离——仅见 project_role_assignment 中 employee_id=当前用户 的项目
    @Test
    void pmShouldOnlySeeAssignedProjects() {
        seedRole("PM_ROLE_LIST");
        seedEmployee("PM_EMP_1");
        seedUser("U_PM_1", "pm_list_test", "PM_EMP_1");

        projectService.createProject(newProject("PRJ_A", "P2"));
        projectService.createProject(newProject("PRJ_B", "P3"));
        projectService.createProject(newProject("PRJ_C", "P2"));

        // PM 只负责 PRJ_A(P2)、PRJ_B(P3)，不负责 PRJ_C
        seedAssignment("PRJ_A", "P2", "PM_ROLE_LIST", "PM_EMP_1");
        seedAssignment("PRJ_B", "P3", "PM_ROLE_LIST", "PM_EMP_1");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_list_test", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"))));

        PageResult<ProjectDTO> result = projectService.listProjects(
                new PageQuery(), null, null, false, null, null);

        List<String> codes = result.getList().stream().map(ProjectDTO::getProjectCode).toList();
        assertEquals(2, result.getTotal());
        assertTrue(codes.contains("PRJ_A"));
        assertTrue(codes.contains("PRJ_B"));
        assertFalse(codes.contains("PRJ_C"));
    }

    // 功能：ADMIN 不过滤——看到全部项目（含未分配给自己的项目）
    @Test
    void adminShouldSeeAllProjects() {
        seedRole("PM_ROLE_LIST");
        seedEmployee("PM_EMP_2");
        seedUser("U_ADMIN_1", "admin_list_test", "PM_EMP_2");

        projectService.createProject(newProject("PRJ_X", "P2"));
        projectService.createProject(newProject("PRJ_Y", "P3"));
        projectService.createProject(newProject("PRJ_Z", "P4"));

        // ADMIN 只负责 PRJ_X，但应看到全部三个
        seedAssignment("PRJ_X", "P2", "PM_ROLE_LIST", "PM_EMP_2");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin_list_test", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        PageResult<ProjectDTO> result = projectService.listProjects(
                new PageQuery(), null, null, false, null, null);

        assertEquals(3, result.getTotal());
    }

    // 功能：PM 创建项目后自动写入 PM 角色分配，且新建项目立即出现在其项目列表
    @Test
    void pmCreateProjectShouldAutoAssignPmRoleAndAppearInList() {
        seedRole("PM");
        seedEmployee("PM_EMP_3");
        seedUser("U_PM_3", "pm_creator", "PM_EMP_3");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_creator", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"))));

        projectService.createProject(newProject("PRJ_SELF", "P2"));

        // 1. 自动写入了 PM 角色分配（is_primary_pd=true）
        ProjectRoleAssignment assign = roleAssignmentMapper.selectOne(
                new LambdaQueryWrapper<ProjectRoleAssignment>()
                        .eq(ProjectRoleAssignment::getProjectCode, "PRJ_SELF")
                        .eq(ProjectRoleAssignment::getProjectStage, "P2")
                        .eq(ProjectRoleAssignment::getProjectRoleCode, "PM")
                        .eq(ProjectRoleAssignment::getEmployeeId, "PM_EMP_3"));
        assertNotNull(assign);
        assertEquals(Boolean.TRUE, assign.getIsPrimaryPd());

        // 2. 新建项目立即出现在 PM 的项目列表
        PageResult<ProjectDTO> result = projectService.listProjects(
                new PageQuery(), null, null, false, null, null);
        List<String> codes = result.getList().stream().map(ProjectDTO::getProjectCode).toList();
        assertEquals(1, result.getTotal());
        assertTrue(codes.contains("PRJ_SELF"));
    }

    // 辅助：插入项目角色
    private void seedRole(String roleCode) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode(roleCode);
        role.setRoleName("项目经理角色");
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

    // 辅助：插入角色分配
    private void seedAssignment(String projectCode, String stage, String roleCode, String employeeId) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode(roleCode);
        a.setEmployeeId(employeeId);
        a.setIsPrimaryPd(false);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：构建项目
    private Project newProject(String code, String stage) {
        Project project = new Project();
        project.setProjectCode(code);
        project.setProjectName("项目" + code);
        project.setProjectStage(stage);
        return project;
    }
}
