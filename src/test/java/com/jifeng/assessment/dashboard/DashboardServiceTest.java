// 模块用途：DashboardService 单元测试——覆盖空库统计、部分数据统计、差异报告
// 依赖文件：DashboardService.java, EmployeeMapper.java, ProjectRoleMapper.java, ProjectMapper.java, PositionConfigMapper.java, ProjectKpiMapper.java, FuncKpiMapper.java
// 修改注意：用现有Mapper写入测试数据，每个测试独立回滚
package com.jifeng.assessment.dashboard;

import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
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

    // 功能：仅种子数据时——admin员工已配置，其余模块为待配置
    @Test
    void shouldShowOnlySeedDataWhenNoOtherData() {
        // DataInitializer creates 1 admin employee
        List<DashboardService.ConfigProgressItem> items = dashboardService.configProgress();
        assertEquals(5, items.size());
        assertEquals(1, getCount(items, "employee"), "seed admin employee");
        assertEquals("已配置", getStatus(items, "employee"));
        assertEquals(0, getCount(items, "projectRole"));
        assertEquals("待配置", getStatus(items, "projectRole"));
        assertEquals(0, getCount(items, "project"));
        assertEquals("待配置", getStatus(items, "project"));
        assertEquals(0, getCount(items, "positionConfig"));
        assertEquals("待配置", getStatus(items, "positionConfig"));
        assertEquals(0, getCount(items, "kpi"));
        assertEquals("待配置", getStatus(items, "kpi"));
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
        assertEquals(1, getCount(items, "projectRole"));
        assertEquals(1, getCount(items, "project"));
        assertEquals(1, getCount(items, "positionConfig"));
        assertEquals(1, getCount(items, "kpi"));

        for (DashboardService.ConfigProgressItem item : items) {
            assertEquals("已配置", item.status(), item.key() + " should be 已配置");
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
        assertEquals("已配置", getStatus(items, "employee"));
        assertEquals(0, getCount(items, "projectRole"));
        assertEquals("待配置", getStatus(items, "projectRole"));
        assertEquals(0, getCount(items, "project"));
        assertEquals("待配置", getStatus(items, "project"));
    }

    // 功能：diffReport在阶段1返回空列表
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
