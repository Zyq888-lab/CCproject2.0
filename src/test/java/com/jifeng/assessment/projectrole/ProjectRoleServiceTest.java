// 模块用途：ProjectRoleService 单元测试——覆盖新增、修改、删除、引用检查、停用等场景
// 依赖文件：ProjectRoleService.java, ProjectRoleMapper.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.projectrole;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProjectRoleServiceTest {

    @Autowired
    private ProjectRoleService projectRoleService;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private ProjectRoleAssignmentMapper roleAssignmentMapper;

    @Autowired
    private ProjectKpiMapper projectKpiMapper;

    // 功能：新增角色成功，roleCode和roleName正确返回
    @Test
    void shouldCreateProjectRole() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("QA");
        role.setRoleName("质量代表");
        role.setDescription("负责质量审查");

        ProjectRoleDTO dto = projectRoleService.createProjectRole(role);
        assertNotNull(dto);
        assertEquals("QA", dto.getRoleCode());
        assertEquals("质量代表", dto.getRoleName());
        assertTrue(dto.getIsActive());
    }

    // 功能：roleCode为空时抛出400异常
    @Test
    void shouldRejectEmptyRoleCode() {
        ProjectRole role = new ProjectRole();
        role.setRoleName("测试");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectRoleService.createProjectRole(role));
        assertTrue(ex.getMessage().contains("角色代码不能为空"));
    }

    // 功能：roleCode重复时抛出409异常
    @Test
    void shouldRejectDuplicateRoleCode() {
        ProjectRole role1 = new ProjectRole();
        role1.setRoleCode("PDL");
        role1.setRoleName("项目负责人");
        projectRoleService.createProjectRole(role1);

        ProjectRole role2 = new ProjectRole();
        role2.setRoleCode("PDL");
        role2.setRoleName("重复角色");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectRoleService.createProjectRole(role2));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    // 功能：修改角色名称和描述成功
    @Test
    void shouldUpdateProjectRole() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("PQL");
        role.setRoleName("项目质量负责人");
        projectRoleService.createProjectRole(role);

        ProjectRole update = new ProjectRole();
        update.setRoleName("质量组长");
        update.setDescription("更新后的描述");

        ProjectRoleDTO dto = projectRoleService.updateProjectRole("PQL", update);
        assertEquals("质量组长", dto.getRoleName());
        assertEquals("更新后的描述", dto.getDescription());
    }

    // 功能：删除未被引用的角色成功
    @Test
    void shouldDeleteUnreferencedRole() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("TEMP");
        role.setRoleName("临时角色");
        projectRoleService.createProjectRole(role);

        assertDoesNotThrow(() -> projectRoleService.deleteProjectRole("TEMP"));

        // 逻辑删除后查询不到
        ProjectRole deleted = new ProjectRole();
        deleted.setRoleCode("TEMP");
        deleted.setRoleName("test");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectRoleService.updateProjectRole("TEMP", deleted));
        assertTrue(ex.getMessage().contains("角色不存在"));
    }

    // 功能：停用角色后 isActive 变为 false
    @Test
    void shouldToggleProjectRole() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("LAUNCH");
        role.setRoleName("投产负责人");
        projectRoleService.createProjectRole(role);

        ProjectRoleDTO toggled = projectRoleService.toggleProjectRole("LAUNCH");
        assertFalse(toggled.getIsActive());

        ProjectRoleDTO toggledBack = projectRoleService.toggleProjectRole("LAUNCH");
        assertTrue(toggledBack.getIsActive());
    }

    // 功能：按 isActive 过滤查询
    @Test
    void shouldFilterByIsActive() {
        ProjectRole active = new ProjectRole();
        active.setRoleCode("ACTIVE_ROLE");
        active.setRoleName("活跃角色");
        active.setIsActive(true);
        projectRoleService.createProjectRole(active);

        ProjectRole inactive = new ProjectRole();
        inactive.setRoleCode("INACTIVE_ROLE");
        inactive.setRoleName("停用角色");
        inactive.setIsActive(false);
        projectRoleService.createProjectRole(inactive);

        List<ProjectRoleDTO> activeOnly = projectRoleService.listProjectRoles(true);
        assertTrue(activeOnly.stream().anyMatch(r -> "ACTIVE_ROLE".equals(r.getRoleCode())));
        assertTrue(activeOnly.stream().noneMatch(r -> "INACTIVE_ROLE".equals(r.getRoleCode())));

        List<ProjectRoleDTO> all = projectRoleService.listProjectRoles(null);
        assertTrue(all.stream().anyMatch(r -> "ACTIVE_ROLE".equals(r.getRoleCode())));
        assertTrue(all.stream().anyMatch(r -> "INACTIVE_ROLE".equals(r.getRoleCode())));
    }

    // 功能：删除有分配记录的角色时应抛出异常
    @Test
    void testDeleteRoleWithAssignmentsShouldFail() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("PDL");
        role.setRoleName("项目总监");
        projectRoleService.createProjectRole(role);

        Project project = new Project();
        project.setProjectCode("PROJ_TEST");
        project.setProjectName("测试项目");
        project.setProjectStage("P2");
        projectMapper.insert(project);

        Employee employee = new Employee();
        employee.setEmployeeId("EMP001");
        employee.setName("测试员工");
        employee.setEmail("emp001@test.com");
        employee.setCategory("研发技术类");
        employee.setPosition("工程师");
        employee.setOrgName("测试部门");
        employeeMapper.insert(employee);

        ProjectRoleAssignment assignment = new ProjectRoleAssignment();
        assignment.setProjectCode("PROJ_TEST");
        assignment.setProjectRoleCode("PDL");
        assignment.setEmployeeId("EMP001");
        roleAssignmentMapper.insert(assignment);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectRoleService.deleteProjectRole("PDL"));
        assertTrue(ex.getMessage().contains("项目角色分配记录引用"));
    }

    // 功能：删除有KPI配置引用的角色时应抛出异常
    @Test
    void testDeleteRoleWithKpiConfigShouldFail() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("PDL");
        role.setRoleName("项目总监");
        projectRoleService.createProjectRole(role);

        ProjectKpiConfig kpi = new ProjectKpiConfig();
        kpi.setProjectRoleCode("PDL");
        kpi.setProjectStage("P2");
        kpi.setKpiName("技术方案质量");
        kpi.setWeight(new java.math.BigDecimal("0.3000"));
        projectKpiMapper.insert(kpi);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectRoleService.deleteProjectRole("PDL"));
        assertTrue(ex.getMessage().contains("项目KPI配置引用"));
    }

    // 功能：逻辑删除后重新创建同名角色应成功
    @Test
    void testRecreateDeletedRoleShouldSucceed() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("PDL");
        role.setRoleName("项目总监");
        ProjectRoleDTO created = projectRoleService.createProjectRole(role);
        assertEquals("PDL", created.getRoleCode());
        assertEquals("项目总监", created.getRoleName());

        projectRoleService.deleteProjectRole("PDL");

        ProjectRole role2 = new ProjectRole();
        role2.setRoleCode("PDL");
        role2.setRoleName("新项目总监");
        role2.setDescription("重建后的角色");
        ProjectRoleDTO recreated = projectRoleService.createProjectRole(role2);
        assertEquals("PDL", recreated.getRoleCode());
        assertEquals("新项目总监", recreated.getRoleName());
        assertEquals("重建后的角色", recreated.getDescription());

        List<ProjectRoleDTO> list = projectRoleService.listProjectRoles(null);
        long count = list.stream().filter(r -> "PDL".equals(r.getRoleCode())).count();
        assertEquals(1, count);
    }
}
