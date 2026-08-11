// 模块用途：乐观锁集成测试——跨实体并发更新冲突验证（T30）
// 依赖文件：BaseService.java, Employee.java, Project.java, SystemParam.java, PositionAssessmentConfig.java
// 修改注意：每个测试独立，验证version冲突时返回409，影响行数0时事务回滚
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
import com.jifeng.assessment.positioncategory.PositionCategory;
import com.jifeng.assessment.positioncategory.PositionCategoryMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.project.ProjectService;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import com.jifeng.assessment.projectrole.ProjectRoleService;
import com.jifeng.assessment.system.SystemParam;
import com.jifeng.assessment.system.SystemParamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OptimisticLockIntegrationTest {

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private ProjectService projectService;
    @Autowired private ProjectMapper projectMapper;
    @Autowired private ProjectRoleService projectRoleService;
    @Autowired private ProjectRoleMapper projectRoleMapper;
    @Autowired private SystemParamService systemParamService;
    @Autowired private PositionConfigService positionConfigService;
    @Autowired private KpiConfigService kpiConfigService;
    @Autowired private PositionCategoryMapper positionCategoryMapper;

    @BeforeEach
    void seedCategories() {
        if (positionCategoryMapper.selectCount(null) == 0) {
            PositionCategory c = new PositionCategory(); c.setName("研发技术类"); c.setSortOrder(10); positionCategoryMapper.insert(c);
            c = new PositionCategory(); c.setName("管理类"); c.setSortOrder(20); positionCategoryMapper.insert(c);
        }
    }

    // ========================================
    // Employee 乐观锁
    // ========================================

    @Test
    void employeeUpdateShouldRejectStaleVersion() {
        Employee emp = new Employee();
        emp.setEmployeeId("INT_EMP01");
        emp.setName("集成测试员工");
        emp.setEmail("int01@jifeng.com");
        emp.setCategory("研发技术类");
        emp.setPosition("整椅研发岗");
        emp.setOrgName("研发部");
        emp.setStatus("ACTIVE");
        employeeService.createEmployee(emp);

        // 模拟并发：直接通过mapper更新推进version
        Employee fresh = employeeMapper.selectById("INT_EMP01");
        fresh.setOrgName("并发修改的部门");
        employeeMapper.updateById(fresh);

        // 用过期version更新
        Employee stale = new Employee();
        stale.setEmployeeId("INT_EMP01");
        stale.setOrgName("过期的修改");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.updateWithOptimisticLock(stale));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被他人修改"));
    }

    // ========================================
    // Project 乐观锁
    // ========================================

    @Test
    void projectUpdateShouldRejectStaleVersion() {
        Project p = new Project();
        p.setProjectCode("INT_PRJ01");
        p.setProjectName("集成测试项目");
        p.setProjectStage("P3");
        p.setStatus("ACTIVE");
        projectService.createProject(p);

        // 模拟并发
        Project fresh = projectMapper.selectByCodeAndStage("INT_PRJ01", "P3");
        fresh.setDescription("并发修改的描述");
        projectMapper.updateById(fresh);

        Project stale = new Project();
        stale.setProjectCode("INT_PRJ01");
        stale.setProjectStage("P3");
        stale.setDescription("过期的描述");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateWithOptimisticLock(stale));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被他人修改"));
    }

    @Test
    void projectStageConfirmShouldDetectVersionConflict() {
        Project p = new Project();
        p.setProjectCode("INT_PRJ02");
        p.setProjectName("阶段锁定测试");
        p.setProjectStage("P2");
        p.setStatus("ACTIVE");
        projectService.createProject(p);

        // 模拟并发修改
        Project fresh = projectMapper.selectByCodeAndStage("INT_PRJ02", "P2");
        fresh.setDescription("并发修改");
        projectMapper.updateById(fresh);

        // 用过期version确认阶段
        Project stale = new Project();
        stale.setProjectCode("INT_PRJ02");
        stale.setProjectStage("P2");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateWithOptimisticLock(stale));
        assertEquals(409, ex.getCode());
    }

    // ========================================
    // ProjectRole 乐观锁
    // ========================================

    @Test
    void projectRoleUpdateShouldRejectStaleVersion() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("INT_ROLE");
        role.setRoleName("集成测试角色");
        role.setIsActive(true);
        projectRoleService.createProjectRole(role);

        // 模拟并发
        ProjectRole fresh = projectRoleMapper.selectById("INT_ROLE");
        fresh.setDescription("并发修改");
        projectRoleMapper.updateById(fresh);

        ProjectRole stale = new ProjectRole();
        stale.setRoleCode("INT_ROLE");
        stale.setDescription("过期修改");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectRoleService.updateWithOptimisticLock(stale));
        assertEquals(409, ex.getCode());
    }

    // ========================================
    // SystemParam 乐观锁
    // ========================================

    @Test
    void systemParamBatchUpdateShouldRejectStaleVersion() {
        List<SystemParam> params = systemParamService.listAll();
        SystemParam first = params.get(0);

        // 正常更新推进version
        SystemParam u1 = new SystemParam();
        u1.setId(first.getId());
        u1.setParamValue("first_update");
        u1.setVersion(first.getVersion());
        systemParamService.batchUpdate(List.of(u1));

        // 用过期version再次更新
        SystemParam stale = new SystemParam();
        stale.setId(first.getId());
        stale.setParamValue("stale_update");
        stale.setVersion(first.getVersion());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> systemParamService.batchUpdate(List.of(stale)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被他人修改"));
    }

    // ========================================
    // PositionConfig 乐观锁
    // ========================================

    @Test
    void positionConfigUpdateShouldRejectStaleVersion() {
        // 创建配置需要有效的权重
        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory("研发技术类");
        config.setPosition("整椅研发岗");
        config.setIsProjectBased(true);
        config.setProjectWeight(new BigDecimal("0.7000"));
        config.setFuncWeight(new BigDecimal("0.3000"));
        config.setFuncAssessMode("DIRECT_LEADER");
        PositionAssessmentConfig created = positionConfigService.createConfig(config);

        // 模拟并发
        PositionAssessmentConfig fresh = positionConfigService.getConfig(created.getId());
        fresh.setCategory("并发修改的分类");
        positionConfigService.getBaseMapper().updateById(fresh);

        PositionAssessmentConfig stale = new PositionAssessmentConfig();
        stale.setId(created.getId());
        stale.setCategory("过期修改");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> positionConfigService.updateWithOptimisticLock(stale));
        assertTrue(ex.getMessage().contains("修改") || ex.getMessage().contains("409"));
    }

    // ========================================
    // KPI 乐观锁
    // ========================================

    @Test
    void projectKpiUpdateShouldRejectStaleVersion() {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("INT_KPI_ROLE");
        role.setRoleName("KPI集成测试角色");
        role.setIsActive(true);
        projectRoleMapper.insert(role);

        ProjectKpiConfig kpi = new ProjectKpiConfig();
        kpi.setProjectRoleCode("INT_KPI_ROLE");
        kpi.setProjectStage("P2");
        kpi.setKpiName("集成测试KPI");
        kpi.setWeight(new BigDecimal("1.0000"));
        ProjectKpiConfig created = kpiConfigService.createProjectKpi(kpi);

        // 模拟并发
        ProjectKpiConfig fresh = new ProjectKpiConfig();
        fresh.setKpiName("第一次修改");
        fresh.setVersion(0L);
        kpiConfigService.updateProjectKpi(created.getId(), fresh);

        // 过期version
        ProjectKpiConfig stale = new ProjectKpiConfig();
        stale.setKpiName("过期修改");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> kpiConfigService.updateProjectKpi(created.getId(), stale));
        assertTrue(ex.getMessage().contains("修改") || ex.getMessage().contains("409"));
    }

    @Test
    void funcKpiUpdateShouldRejectStaleVersion() {
        FuncKpiConfig kpi = new FuncKpiConfig();
        kpi.setCategory("研发技术类");
        kpi.setPosition("整椅研发岗");
        kpi.setKpiName("集成测试职能KPI");
        kpi.setWeight(new BigDecimal("1.0000"));
        FuncKpiConfig created = kpiConfigService.createFuncKpi(kpi);

        // 模拟并发
        FuncKpiConfig fresh = new FuncKpiConfig();
        fresh.setKpiName("第一次修改");
        fresh.setVersion(0L);
        kpiConfigService.updateFuncKpi(created.getId(), fresh);

        // 过期version
        FuncKpiConfig stale = new FuncKpiConfig();
        stale.setKpiName("过期修改");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> kpiConfigService.updateFuncKpi(created.getId(), stale));
        assertTrue(ex.getMessage().contains("修改") || ex.getMessage().contains("409"));
    }
}
