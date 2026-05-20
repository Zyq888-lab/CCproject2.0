// 模块用途：EmployeeService 单元测试——覆盖新增校验、工号重复、直属上级不存在等场景
// 依赖文件：EmployeeService.java, EmployeeMapper.java, Employee.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.employee;

import com.jifeng.assessment.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EmployeeServiceTest {

    @Autowired
    private EmployeeService employeeService;

    // 功能：正常新增员工，校验返回的DTO字段与输入一致
    @Test
    void shouldCreateEmployee() {
        Employee emp = new Employee();
        emp.setEmployeeId("EMP001");
        emp.setName("张三");
        emp.setEmail("zhangsan@jifeng.com");
        emp.setCategory("研发技术类");
        emp.setPosition("整椅研发岗");
        emp.setOrgName("研发部");
        emp.setStatus("ACTIVE");

        EmployeeDTO dto = employeeService.createEmployee(emp);
        assertNotNull(dto);
        assertEquals("EMP001", dto.getEmployeeId());
        assertEquals("张三", dto.getName());
    }

    // 功能：工号重复时抛出409异常
    @Test
    void shouldRejectDuplicateEmployeeId() {
        Employee emp1 = new Employee();
        emp1.setEmployeeId("EMP002");
        emp1.setName("李四");
        emp1.setEmail("lisi@jifeng.com");
        emp1.setCategory("研发技术类");
        emp1.setPosition("整椅研发岗");
        emp1.setOrgName("研发部");
        emp1.setStatus("ACTIVE");
        employeeService.createEmployee(emp1);

        Employee emp2 = new Employee();
        emp2.setEmployeeId("EMP002");
        emp2.setName("王五");
        emp2.setEmail("wangwu@jifeng.com");
        emp2.setCategory("研发技术类");
        emp2.setPosition("整椅研发岗");
        emp2.setOrgName("研发部");
        emp2.setStatus("ACTIVE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.createEmployee(emp2));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    // 功能：工号为空时抛出400异常
    @Test
    void shouldRejectEmptyEmployeeId() {
        Employee emp = new Employee();
        emp.setName("测试");
        emp.setEmail("test@jifeng.com");
        emp.setCategory("研发技术类");
        emp.setPosition("整椅研发岗");
        emp.setOrgName("研发部");
        emp.setStatus("ACTIVE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.createEmployee(emp));
        assertTrue(ex.getMessage().contains("工号不能为空"));
    }

    // 功能：姓名为空时抛出400异常
    @Test
    void shouldRejectEmptyName() {
        Employee emp = new Employee();
        emp.setEmployeeId("EMP003");
        emp.setEmail("test@jifeng.com");
        emp.setCategory("研发技术类");
        emp.setPosition("整椅研发岗");
        emp.setOrgName("研发部");
        emp.setStatus("ACTIVE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.createEmployee(emp));
        assertTrue(ex.getMessage().contains("姓名不能为空"));
    }

    // 功能：直属上级工号不存在时抛出400异常
    @Test
    void shouldRejectNonexistentLeader() {
        Employee emp = new Employee();
        emp.setEmployeeId("EMP004");
        emp.setName("赵六");
        emp.setEmail("zhaoliu@jifeng.com");
        emp.setCategory("研发技术类");
        emp.setPosition("整椅研发岗");
        emp.setOrgName("研发部");
        emp.setDirectLeaderId("NONEXISTENT");
        emp.setStatus("ACTIVE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.createEmployee(emp));
        assertTrue(ex.getMessage().contains("直属上级工号不存在"));
    }

    // 功能：根据工号查询员工，返回正确的员工信息
    @Test
    void shouldGetEmployeeById() {
        Employee emp = new Employee();
        emp.setEmployeeId("EMP005");
        emp.setName("孙七");
        emp.setEmail("sunqi@jifeng.com");
        emp.setCategory("管理类");
        emp.setPosition("项目经理");
        emp.setOrgName("项目部");
        emp.setStatus("ACTIVE");
        employeeService.createEmployee(emp);

        EmployeeDTO dto = employeeService.getEmployee("EMP005");
        assertNotNull(dto);
        assertEquals("孙七", dto.getName());
    }

    // 功能：分页查询员工列表，验证分页参数和总数
    @Test
    void shouldListEmployeesWithPagination() {
        for (int i = 1; i <= 5; i++) {
            Employee emp = new Employee();
            emp.setEmployeeId("EMP_P" + i);
            emp.setName("分页测试" + i);
            emp.setEmail("page" + i + "@jifeng.com");
            emp.setCategory("研发技术类");
            emp.setPosition("整椅研发岗");
            emp.setOrgName("研发部");
            emp.setStatus("ACTIVE");
            employeeService.createEmployee(emp);
        }

        com.jifeng.assessment.common.PageQuery query = new com.jifeng.assessment.common.PageQuery();
        query.setPage(1);
        query.setSize(3);
        com.jifeng.assessment.common.PageResult<EmployeeDTO> result =
                employeeService.listEmployees(query, null, null);

        // ADMIN 种子数据 + 5 条测试数据 = 6
        assertEquals(6, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(3, result.getSize());
        assertEquals(3, result.getList().size());
    }
}
