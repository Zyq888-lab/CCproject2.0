// 模块用途：唯一约束集成测试——重复工号/项目代码/角色代码/用户名导致约束异常（T30）
// 依赖文件：各Service和Mapper类
// 修改注意：需要实际触发数据库唯一约束，验证异常被正确包装为BusinessException(409)
package com.jifeng.assessment.integration;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.employee.EmployeeService;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.KpiConfigService;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigService;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectService;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleService;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.RoleAssignmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UniqueConstraintIntegrationTest {

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private ProjectService projectService;
    @Autowired private ProjectRoleService projectRoleService;
    @Autowired private RoleAssignmentService roleAssignmentService;
    @Autowired private PositionConfigService positionConfigService;
    @Autowired private KpiConfigService kpiConfigService;

    // ========================================
    // 员工工号唯一约束
    // ========================================

    @Test
    void duplicateEmployeeIdViaServiceShouldThrow409() {
        Employee emp1 = new Employee();
        emp1.setEmployeeId("UNQ_EMP001");
        emp1.setName("张三");
        emp1.setEmail("zhang@jifeng.com");
        emp1.setCategory("研发技术类");
        emp1.setPosition("整椅研发岗");
        emp1.setOrgName("研发部");
        emp1.setStatus("ACTIVE");
        employeeService.createEmployee(emp1);

        Employee emp2 = new Employee();
        emp2.setEmployeeId("UNQ_EMP001");
        emp2.setName("李四");
        emp2.setEmail("lisi@jifeng.com");
        emp2.setCategory("研发技术类");
        emp2.setPosition("整椅研发岗");
        emp2.setOrgName("研发部");
        emp2.setStatus("ACTIVE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.createEmployee(emp2));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void duplicateEmployeeIdViaMapperShouldThrowDuplicateKeyException() {
        Employee emp1 = new Employee();
        emp1.setEmployeeId("UNQ_EMP002");
        emp1.setName("王五");
        emp1.setEmail("wang@jifeng.com");
        emp1.setCategory("研发技术类");
        emp1.setPosition("整椅研发岗");
        emp1.setOrgName("研发部");
        emp1.setStatus("ACTIVE");
        employeeMapper.insert(emp1);

        Employee emp2 = new Employee();
        emp2.setEmployeeId("UNQ_EMP002");
        emp2.setName("赵六");
        emp2.setEmail("zhao@jifeng.com");
        emp2.setCategory("研发技术类");
        emp2.setPosition("整椅研发岗");
        emp2.setOrgName("研发部");
        emp2.setStatus("ACTIVE");

        assertThrows(DuplicateKeyException.class,
                () -> employeeMapper.insert(emp2));
    }

    // ========================================
    // 项目代码唯一约束
    // ========================================

    @Test
    void duplicateProjectCodeShouldThrow409() {
        Project p1 = new Project();
        p1.setProjectCode("UNQ_PRJ001");
        p1.setProjectName("唯一约束测试项目");
        p1.setProjectStage("P2");
        p1.setStatus("ACTIVE");
        projectService.createProject(p1);

        Project p2 = new Project();
        p2.setProjectCode("UNQ_PRJ001");
        p2.setProjectName("重复项目代码");
        p2.setProjectStage("P3");
        p2.setStatus("ACTIVE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.createProject(p2));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    // ========================================
    // 项目角色代码唯一约束
    // ========================================

    @Test
    void duplicateRoleCodeShouldThrow409() {
        ProjectRole r1 = new ProjectRole();
        r1.setRoleCode("UNQ_ROLE");
        r1.setRoleName("唯一角色");
        r1.setIsActive(true);
        projectRoleService.createProjectRole(r1);

        ProjectRole r2 = new ProjectRole();
        r2.setRoleCode("UNQ_ROLE");
        r2.setRoleName("重复角色代码");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectRoleService.createProjectRole(r2));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    // ========================================
    // 角色分配唯一约束（同项目同角色同员工）
    // ========================================

    @Test
    void duplicateAssignmentShouldThrow409() {
        // Setup
        Employee emp = new Employee();
        emp.setEmployeeId("UNQ_ASGN_EMP");
        emp.setName("分配测试");
        emp.setEmail("assign@jifeng.com");
        emp.setCategory("研发技术类");
        emp.setPosition("整椅研发岗");
        emp.setOrgName("研发部");
        emp.setStatus("ACTIVE");
        employeeMapper.insert(emp);

        Project proj = new Project();
        proj.setProjectCode("UNQ_ASGN_PRJ");
        proj.setProjectName("分配约束测试");
        proj.setProjectStage("P3");
        proj.setStatus("ACTIVE");
        proj.setStageConfirmed(false);
        projectService.createProject(proj);

        ProjectRole role = new ProjectRole();
        role.setRoleCode("UNQ_ASGN_ROLE");
        role.setRoleName("分配测试角色");
        role.setIsActive(true);
        projectRoleService.createProjectRole(role);

        // 第一次分配
        roleAssignmentService.assignEmployee("UNQ_ASGN_PRJ", "UNQ_ASGN_ROLE", "UNQ_ASGN_EMP");

        // 重复分配
        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleAssignmentService.assignEmployee("UNQ_ASGN_PRJ", "UNQ_ASGN_ROLE", "UNQ_ASGN_EMP"));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被分配"));
    }

    // ========================================
    // KPI指标名称唯一约束
    // ========================================

    @Test
    void duplicateProjectKpiNameShouldThrow409() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("UNQ_KPI_ROLE");
        role.setRoleName("KPI唯一角色");
        role.setIsActive(true);
        projectRoleService.createProjectRole(role);

        ProjectKpiConfig kpi1 = new ProjectKpiConfig();
        kpi1.setProjectRoleCode("UNQ_KPI_ROLE");
        kpi1.setProjectStage("P2");
        kpi1.setKpiName("唯一指标名");
        kpi1.setWeight(new BigDecimal("1.0000"));
        kpiConfigService.createProjectKpi(kpi1);

        ProjectKpiConfig kpi2 = new ProjectKpiConfig();
        kpi2.setProjectRoleCode("UNQ_KPI_ROLE");
        kpi2.setProjectStage("P2");
        kpi2.setKpiName("唯一指标名");
        kpi2.setWeight(new BigDecimal("1.0000"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> kpiConfigService.createProjectKpi(kpi2));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在指标"));
    }

    @Test
    void duplicateFuncKpiNameShouldThrow409() {
        FuncKpiConfig kpi1 = new FuncKpiConfig();
        kpi1.setCategory("研发技术类");
        kpi1.setPosition("测试岗");
        kpi1.setKpiName("唯一职能指标");
        kpi1.setWeight(new BigDecimal("1.0000"));
        kpiConfigService.createFuncKpi(kpi1);

        FuncKpiConfig kpi2 = new FuncKpiConfig();
        kpi2.setCategory("研发技术类");
        kpi2.setPosition("测试岗");
        kpi2.setKpiName("唯一职能指标");
        kpi2.setWeight(new BigDecimal("1.0000"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> kpiConfigService.createFuncKpi(kpi2));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在指标"));
    }

    // ========================================
    // 岗位配置唯一约束（同分类同岗位 —— DB 层约束，createConfig 不显式捕获）
    // ========================================

    @Test
    void duplicatePositionConfigShouldTriggerDbConstraint() {
        PositionAssessmentConfig c1 = new PositionAssessmentConfig();
        c1.setCategory("研发技术类");
        c1.setPosition("唯一岗位");
        c1.setIsProjectBased(true);
        c1.setProjectWeight(new BigDecimal("0.7000"));
        c1.setFuncWeight(new BigDecimal("0.3000"));
        c1.setFuncAssessMode("DIRECT_LEADER");
        positionConfigService.createConfig(c1);

        PositionAssessmentConfig c2 = new PositionAssessmentConfig();
        c2.setCategory("研发技术类");
        c2.setPosition("唯一岗位");
        c2.setIsProjectBased(false);
        c2.setProjectWeight(new BigDecimal("0.5000"));
        c2.setFuncWeight(new BigDecimal("0.5000"));
        c2.setFuncAssessMode("SINGLE");

        // createConfig 不捕获 DuplicateKeyException，DB 层直接抛出
        assertThrows(Exception.class,
                () -> positionConfigService.createConfig(c2));
    }
}
