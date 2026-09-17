// 模块用途：RoleAssignmentService 单元测试——覆盖分配人员、标记PD负责人、移除分配
// 依赖文件：RoleAssignmentService.java, ProjectMapper.java, EmployeeMapper.java, ProjectRoleMapper.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.roleassignment;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
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
class RoleAssignmentServiceTest {

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private ProjectRoleMapper projectRoleMapper;

    // 辅助方法：创建测试项目
    private void createTestProject(String code) {
        Project p = new Project();
        p.setProjectCode(code);
        p.setProjectName("测试项目" + code);
        p.setProjectStage("P3");
        p.setStatus("ACTIVE");
        p.setStageConfirmed(false);
        projectMapper.insert(p);
    }

    // 辅助方法：创建测试员工
    private void createTestEmployee(String id, String name) {
        Employee emp = new Employee();
        emp.setEmployeeId(id);
        emp.setName(name);
        emp.setEmail(name + "@jifeng.com");
        emp.setCategory("研发技术类");
        emp.setPosition("整椅研发岗");
        emp.setOrgName("研发部");
        emp.setStatus("ACTIVE");
        employeeMapper.insert(emp);
    }

    // 辅助方法：创建测试角色
    private void createTestRole(String code, String name) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setIsActive(true);
        projectRoleMapper.insert(role);
    }

    // 功能：成功分配员工到项目角色，返回含员工姓名的DTO
    @Test
    void shouldAssignEmployee() {
        createTestProject("PJ_RA1");
        createTestEmployee("EMP_RA1", "张三");
        createTestRole("PD", "PD负责人");

        ProjectRoleAssignmentDTO dto = roleAssignmentService.assignEmployee("PJ_RA1", "P3", "PD", "EMP_RA1");
        assertNotNull(dto);
        assertEquals("PJ_RA1", dto.getProjectCode());
        assertEquals("PD", dto.getProjectRoleCode());
        assertEquals("EMP_RA1", dto.getEmployeeId());
        assertEquals("张三", dto.getEmployeeName());
        assertFalse(dto.getIsPrimary());
    }

    // 功能：重复分配同一员工到同一项目同一角色时抛出409异常
    @Test
    void shouldRejectDuplicateAssignment() {
        createTestProject("PJ_RA2");
        createTestEmployee("EMP_RA2", "李四");
        createTestRole("PM", "项目经理");

        roleAssignmentService.assignEmployee("PJ_RA2", "P3", "PM", "EMP_RA2");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleAssignmentService.assignEmployee("PJ_RA2", "P3", "PM", "EMP_RA2"));
        assertTrue(ex.getMessage().contains("已被分配"));
    }

    // 功能：标记该角色主后isPrimary为true，同(项目,阶段,角色)之前的主被取消
    @Test
    void shouldMarkPrimary() {
        createTestProject("PJ_RA3");
        createTestEmployee("EMP_RA3A", "王五");
        createTestEmployee("EMP_RA3B", "赵六");
        createTestRole("PD", "PD负责人");

        ProjectRoleAssignmentDTO a1 = roleAssignmentService.assignEmployee("PJ_RA3", "P3", "PD", "EMP_RA3A");
        ProjectRoleAssignmentDTO a2 = roleAssignmentService.assignEmployee("PJ_RA3", "P3", "PD", "EMP_RA3B");

        // 标记第一个为该角色主
        ProjectRoleAssignmentDTO pd1 = roleAssignmentService.markPrimary(a1.getId());
        assertTrue(pd1.getIsPrimary());

        // 标记第二个为该角色主，第一个应被取消
        ProjectRoleAssignmentDTO pd2 = roleAssignmentService.markPrimary(a2.getId());
        assertTrue(pd2.getIsPrimary());

        // 查询列表验证只有一个主PD
        List<ProjectRoleAssignmentDTO> list = roleAssignmentService.listAssignments("PJ_RA3", "P3");
        long primaryCount = list.stream().filter(ProjectRoleAssignmentDTO::getIsPrimary).count();
        assertEquals(1, primaryCount);
    }

    // 功能：取消主标记后 isPrimary 变为 false，但角色分配仍保留在列表中
    @Test
    void shouldUnmarkPrimary() {
        createTestProject("PJ_RA8");
        createTestEmployee("EMP_RA8", "钱十");
        createTestRole("PD", "PD负责人");

        ProjectRoleAssignmentDTO dto = roleAssignmentService.assignEmployee("PJ_RA8", "P3", "PD", "EMP_RA8");
        ProjectRoleAssignmentDTO primary = roleAssignmentService.markPrimary(dto.getId());
        assertTrue(primary.getIsPrimary());

        ProjectRoleAssignmentDTO unmarked = roleAssignmentService.unmarkPrimary(dto.getId());
        assertFalse(unmarked.getIsPrimary());

        // 角色分配未被移除，仍可查询到该记录
        List<ProjectRoleAssignmentDTO> list = roleAssignmentService.listAssignments("PJ_RA8", "P3");
        assertEquals(1, list.size());
        assertFalse(list.get(0).getIsPrimary());
    }

    // 功能：取消非主标记时抛出400
    @Test
    void shouldRejectUnmarkNonPrimary() {
        createTestProject("PJ_RA9");
        createTestEmployee("EMP_RA9", "吴十一");
        createTestRole("PM", "项目经理");

        ProjectRoleAssignmentDTO dto = roleAssignmentService.assignEmployee("PJ_RA9", "P3", "PM", "EMP_RA9");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleAssignmentService.unmarkPrimary(dto.getId()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不是主标记"));
    }

    // 功能：移除分配后查询列表中不再包含该记录
    @Test
    void shouldRemoveAssignment() {
        createTestProject("PJ_RA4");
        createTestEmployee("EMP_RA4", "孙七");
        createTestRole("PQL", "项目质量负责人");

        ProjectRoleAssignmentDTO dto = roleAssignmentService.assignEmployee("PJ_RA4", "P3", "PQL", "EMP_RA4");
        assertNotNull(dto);

        roleAssignmentService.removeAssignment(dto.getId());

        List<ProjectRoleAssignmentDTO> list = roleAssignmentService.listAssignments("PJ_RA4", "P3");
        assertTrue(list.isEmpty());
    }

    // 功能：分配时项目不存在抛出404
    @Test
    void shouldRejectNonexistentProject() {
        createTestEmployee("EMP_RA5", "周八");
        createTestRole("PD", "PD负责人");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleAssignmentService.assignEmployee("NOT_EXIST", "P3", "PD", "EMP_RA5"));
        assertTrue(ex.getMessage().contains("项目不存在"));
    }

    // 功能：分配时员工不存在抛出404
    @Test
    void shouldRejectNonexistentEmployee() {
        createTestProject("PJ_RA6");
        createTestRole("PD", "PD负责人");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleAssignmentService.assignEmployee("PJ_RA6", "P3", "PD", "NOT_EXIST"));
        assertTrue(ex.getMessage().contains("员工不存在"));
    }

    // 功能：分配时角色不存在抛出404
    @Test
    void shouldRejectNonexistentRole() {
        createTestProject("PJ_RA7");
        createTestEmployee("EMP_RA7", "郑九");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleAssignmentService.assignEmployee("PJ_RA7", "P3", "NOT_EXIST", "EMP_RA7"));
        assertTrue(ex.getMessage().contains("项目角色不存在"));
    }
}
