// 模块用途：UserService 单元测试——覆盖创建用户、工号重复、角色分配等场景
// 依赖文件：UserService.java, SysUserMapper.java, EmployeeMapper.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.user;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
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
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private EmployeeMapper employeeMapper;

    // 功能：创建辅助测试员工方法——插入一个员工记录并返回 employeeId
    private String createTestEmployee(String id, String name) {
        Employee emp = new Employee();
        emp.setEmployeeId(id);
        emp.setName(name);
        emp.setEmail(name.toLowerCase() + "@jifeng.com");
        emp.setCategory("研发技术类");
        emp.setPosition("整椅研发岗");
        emp.setOrgName("研发部");
        emp.setStatus("ACTIVE");
        employeeMapper.insert(emp);
        return id;
    }

    // 功能：正常创建用户，密码加密存储，返回DTO不含密码
    @Test
    void shouldCreateUser() {
        createTestEmployee("EMP_U1", "测试用户一");

        UserDTO dto = userService.createUser("EMP_U1", "testuser1", "pass123");
        assertNotNull(dto);
        assertEquals("testuser1", dto.getUsername());
        assertEquals("EMP_U1", dto.getEmployeeId());
        assertEquals("测试用户一", dto.getEmployeeName());
        assertTrue(dto.getEnabled());
    }

    // 功能：同一员工重复创建用户时抛出409异常
    @Test
    void shouldRejectDuplicateEmployeeId() {
        createTestEmployee("EMP_U2", "测试用户二");
        userService.createUser("EMP_U2", "userA", "pass123");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.createUser("EMP_U2", "userB", "pass456"));
        assertTrue(ex.getMessage().contains("已关联系统用户"));
    }

    // 功能：关联不存在的员工工号时抛出400异常
    @Test
    void shouldRejectNonexistentEmployee() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.createUser("NOT_EXIST", "userX", "pass123"));
        assertTrue(ex.getMessage().contains("员工工号不存在"));
    }

    // 功能：分配角色后查询角色列表正确
    @Test
    void shouldUpdateAndQueryRoles() {
        createTestEmployee("EMP_U3", "测试用户三");
        UserDTO dto = userService.createUser("EMP_U3", "testuser3", "pass123");

        List<String> roles = userService.updateUserRoles(dto.getUserId(),
                List.of("PM", "评估人"));
        assertEquals(2, roles.size());
        assertTrue(roles.contains("PM"));
        assertTrue(roles.contains("评估人"));

        List<String> queried = userService.getUserRoles(dto.getUserId());
        assertEquals(2, queried.size());
    }

    // 功能：无效角色类型抛出400异常
    @Test
    void shouldRejectInvalidRoleType() {
        createTestEmployee("EMP_U4", "测试用户四");
        UserDTO dto = userService.createUser("EMP_U4", "testuser4", "pass123");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateUserRoles(dto.getUserId(),
                        List.of("INVALID_ROLE")));
        assertTrue(ex.getMessage().contains("无效的角色类型"));
    }

    // 功能：分页查询用户列表，验证返回的员工姓名和角色正确
    @Test
    void shouldListUsersWithEmployeeInfo() {
        createTestEmployee("EMP_U5", "测试用户五");
        UserDTO created = userService.createUser("EMP_U5", "testuser5", "pass123");
        userService.updateUserRoles(created.getUserId(), List.of("员工"));

        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(20);
        PageResult<UserDTO> result = userService.listUsers(query);

        assertTrue(result.getTotal() >= 2); // ADMIN + created user
        UserDTO found = result.getList().stream()
                .filter(u -> "testuser5".equals(u.getUsername()))
                .findFirst().orElse(null);
        assertNotNull(found);
        assertEquals("测试用户五", found.getEmployeeName());
        assertTrue(found.getRoles().contains("员工"));
    }
}
