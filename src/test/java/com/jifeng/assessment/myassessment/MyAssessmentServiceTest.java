// 模块用途：MyAssessmentService 单元测试——验证「角色分配驱动」的指标聚合：
//   分配角色后无任务也能看到 KPI(状态=待发起/评估人=-)、有任务时回填状态/评估人/周期
// 依赖文件：MyAssessmentService.java, 各 Mapper
// 修改注意：Mockito 模拟全部 Mapper 依赖，不依赖真实数据库
package com.jifeng.assessment.myassessment;

import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MyAssessmentServiceTest {

    @Mock private TaskMapper taskMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectRoleAssignmentMapper roleAssignmentMapper;
    @Mock private ProjectKpiMapper projectKpiMapper;
    @Mock private FuncKpiMapper funcKpiMapper;
    @Mock private EmployeeMapper employeeMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private PeriodMapper periodMapper;

    @InjectMocks
    private MyAssessmentService service;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // 辅助方法：设置当前登录身份并反查员工工号
    private void setAuth(String username, String employeeId) {
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.isAuthenticated()).thenReturn(true);
        lenient().when(auth.getName()).thenReturn(username);
        SecurityContextHolder.getContext().setAuthentication(auth);
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setEmployeeId(employeeId);
        lenient().when(sysUserMapper.selectOne(any())).thenReturn(user);
    }

    private Employee employee(String id, String category, String position, String name) {
        Employee e = new Employee();
        e.setEmployeeId(id);
        e.setName(name);
        e.setCategory(category);
        e.setPosition(position);
        return e;
    }

    private Project project(String code, String name, String stage) {
        Project p = new Project();
        p.setProjectCode(code);
        p.setProjectName(name);
        p.setProjectStage(stage);
        return p;
    }

    private ProjectRoleAssignment assignment(String code, String stage, String roleCode, String empId) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(code);
        a.setProjectStage(stage);
        a.setProjectRoleCode(roleCode);
        a.setEmployeeId(empId);
        a.setDeleted(0);
        return a;
    }

    private ProjectKpiConfig pkpi(String roleCode, String stage, String name) {
        ProjectKpiConfig k = new ProjectKpiConfig();
        k.setProjectRoleCode(roleCode);
        k.setProjectStage(stage);
        k.setKpiName(name);
        k.setWeight(new BigDecimal("0.5"));
        k.setEvaluationCriteria("评价标准");
        k.setSortOrder(1);
        k.setIsActive(true);
        return k;
    }

    private AssessmentPeriod period(String id, String name) {
        AssessmentPeriod p = new AssessmentPeriod();
        p.setPeriodId(id);
        p.setPeriodName(name);
        return p;
    }

    // ========================================
    // 1. 分配项目角色后、无任务 → 仍能看到 KPI，状态=待发起、评估人=-
    // ========================================
    @Test
    void shouldShowKpisWhenRoleAssignedWithoutTask() {
        setAuth("zhanggong", "E003");
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("AIP", "P3", "AIP", "E003")));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectByCodeAndStage("AIP", "P3")).thenReturn(project("AIP", "AIP项目", "P3"));
        when(projectKpiMapper.selectList(any())).thenReturn(List.of(pkpi("AIP", "P3", "ceshi")));
        when(employeeMapper.selectById("E003")).thenReturn(employee("E003", "研发技术类", "整椅研发岗", "张三"));
        when(funcKpiMapper.selectList(any())).thenReturn(List.of());

        List<MyAssessmentItem> items = service.getMyAssessment();

        assertEquals(1, items.size());
        MyAssessmentItem it = items.get(0);
        assertEquals("待发起", it.getStatus());
        assertEquals("-", it.getAssessorName());
        assertEquals(1, it.getKpis().size());
        assertEquals("ceshi", it.getKpis().get(0).getKpiName());
    }

    // ========================================
    // 2. 分配角色后、任务已存在 → 回填任务状态/评估人姓名/周期名
    // ========================================
    @Test
    void shouldBackfillTaskWhenTaskExists() {
        setAuth("zhanggong", "E003");
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("AIP", "P3", "AIP", "E003")));

        AssessmentTask task = new AssessmentTask();
        task.setId(10L);
        task.setAssesseeId("E003");
        task.setAssessorId("ASSESSOR1");
        task.setTaskType("PROJECT");
        task.setProjectCode("AIP");
        task.setProjectStage("P3");
        task.setStatus("IN_PROGRESS");
        task.setPeriodId("P-1");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        when(projectMapper.selectByCodeAndStage("AIP", "P3")).thenReturn(project("AIP", "AIP项目", "P3"));
        when(projectKpiMapper.selectList(any())).thenReturn(List.of(pkpi("AIP", "P3", "ceshi")));
        when(employeeMapper.selectById("ASSESSOR1")).thenReturn(employee("ASSESSOR1", null, null, "评估人甲"));
        when(employeeMapper.selectById("E003")).thenReturn(employee("E003", "研发技术类", "整椅研发岗", "张三"));
        when(funcKpiMapper.selectList(any())).thenReturn(List.of());
        when(periodMapper.selectById("P-1")).thenReturn(period("P-1", "2026 Q3"));

        List<MyAssessmentItem> items = service.getMyAssessment();

        assertEquals(1, items.size());
        MyAssessmentItem it = items.get(0);
        assertEquals(10L, it.getTaskId());
        assertEquals("IN_PROGRESS", it.getStatus());
        assertEquals("评估人甲", it.getAssessorName());
        assertEquals("2026 Q3", it.getPeriodName());
        assertEquals(1, it.getKpis().size());
    }

    // ========================================
    // 3. 登录名无法反查员工 → 返回空列表
    // ========================================
    @Test
    void shouldReturnEmptyWhenNoEmployee() {
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.isAuthenticated()).thenReturn(true);
        lenient().when(auth.getName()).thenReturn("unknown");
        SecurityContextHolder.getContext().setAuthentication(auth);
        lenient().when(sysUserMapper.selectOne(any())).thenReturn(null);

        assertTrue(service.getMyAssessment().isEmpty());
    }

    // ========================================
    // 4. 有 FUNCTIONAL 任务但无职能 KPI 配置 → 不返回空职能项（回归：buildFunctionalItem 空配置直接 null）
    // ========================================
    @Test
    void shouldOmitFunctionalItemWhenNoKpiConfig() {
        setAuth("zhanggong", "E003");
        // 无项目角色分配 → 无 PROJECT 项
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of());
        // 存在一条 FUNCTIONAL 任务（直属上级生成），但 func_kpi_config 为空
        AssessmentTask funcTask = new AssessmentTask();
        funcTask.setId(20L);
        funcTask.setAssesseeId("E003");
        funcTask.setAssessorId("LEADER1");
        funcTask.setTaskType("FUNCTIONAL");
        funcTask.setStatus("PENDING");
        funcTask.setPeriodId("P-1");
        when(taskMapper.selectList(any())).thenReturn(List.of(funcTask));
        when(employeeMapper.selectById("E003")).thenReturn(employee("E003", "研发技术类", "整椅研发岗", "张三"));
        when(funcKpiMapper.selectList(any())).thenReturn(List.of());

        List<MyAssessmentItem> items = service.getMyAssessment();

        assertTrue(items.isEmpty(), "无职能 KPI 配置时不应返回空职能考核项");
    }
}
