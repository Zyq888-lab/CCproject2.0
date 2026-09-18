// 模块用途：PeriodMonitorService 单元测试——当前审批人按周期状态优先映射
// 依赖文件：PeriodMonitorService.java, PeriodMapper.java, EmployeeMapper.java, TaskMapper.java
// 修改注意：@SpringBootTest + @Transactional（测试库 PostgreSQL，每个用例独立回滚）；
//   无鉴权上下文 → getPrimaryRole() 返回空串，不走 PM 项目过滤，聚合全周期任务
package com.jifeng.assessment.monitor;

import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PeriodMonitorServiceTest {

    @Autowired private PeriodMonitorService periodMonitorService;
    @Autowired private PeriodMapper periodMapper;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private ProjectRoleAssignmentMapper projectRoleAssignmentMapper;
    @Autowired private ProjectRoleMapper projectRoleMapper;
    @Autowired private ProjectMapper projectMapper;

    // 功能：CALIBRATING 且 PD 尚未提交校准(calibrationSubmittedAt 为空)时「当前审批人」应为「PD（待校准）」——
    //   旧映射在进入校准后立即显示「总裁（待确认）」，误判了提交状态
    @Test
    void calibratingWithoutSubmissionShouldShowPdPending() {
        seedPeriod("PERIOD-MON-1", "CALIBRATING");
        seedEmployee("EMP-MON-ASR-1");
        seedEmployee("EMP-MON-ASE-1");
        seedTask("PERIOD-MON-1", "EMP-MON-ASR-1", "EMP-MON-ASE-1");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-1");

        assertEquals(1, items.size());
        assertEquals("PD（待校准）", items.get(0).getCurrentApproverName());
        assertNull(items.get(0).getCurrentApproverId());
        assertEquals("CALIBRATING", items.get(0).getPeriodStatus());
    }

    // 功能：CALIBRATING 且 PD 已提交校准(calibrationSubmittedAt 非空)时「当前审批人」应为「总裁（待确认）」
    @Test
    void calibratingWithSubmissionShouldShowPresidentAsApprover() {
        seedPeriodWithSubmittedAt("PERIOD-MON-5", "CALIBRATING", LocalDateTime.now());
        seedEmployee("EMP-MON-ASR-5");
        seedEmployee("EMP-MON-ASE-5");
        seedTask("PERIOD-MON-5", "EMP-MON-ASR-5", "EMP-MON-ASE-5");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-5");

        assertEquals(1, items.size());
        assertEquals("总裁（待确认）", items.get(0).getCurrentApproverName());
        assertNull(items.get(0).getCurrentApproverId());
        assertEquals("CALIBRATING", items.get(0).getPeriodStatus());
    }

    // 功能：CALIBRATING 且未提交校准 + PROJECT 任务带主 PD 时，「当前审批人」应显示具体主 PD 姓名 +「（待校准）」
    @Test
    void calibratingWithoutSubmissionProjectTaskShouldShowPrimaryPdName() {
        seedPeriod("PERIOD-MON-6", "CALIBRATING");
        seedEmployee("EMP-MON-PD-6");
        seedEmployee("EMP-MON-ASR-6");
        seedEmployee("EMP-MON-ASE-6");
        seedProjectTask("PERIOD-MON-6", "EMP-MON-ASR-6", "EMP-MON-ASE-6", "PROJ-MON-2", "STAGE-1");
        seedProject("PROJ-MON-2", "STAGE-1");
        seedRole("PD");
        seedPrimaryPd("PROJ-MON-2", "STAGE-1", "EMP-MON-PD-6");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-6");

        assertEquals(1, items.size());
        assertEquals("员工EMP-MON-PD-6（待校准）", items.get(0).getCurrentApproverName());
        assertEquals("EMP-MON-PD-6", items.get(0).getCurrentApproverId());
        assertEquals("CALIBRATING", items.get(0).getPeriodStatus());
    }

    // 功能：CONFIRMED 终态「当前审批人」应为空——即使任务仍为 SUBMITTED，终态不再有审批人
    @Test
    void confirmedShouldShowNoApprover() {
        seedPeriod("PERIOD-MON-2", "CONFIRMED");
        seedEmployee("EMP-MON-ASR-2");
        seedEmployee("EMP-MON-ASE-2");
        seedTask("PERIOD-MON-2", "EMP-MON-ASR-2", "EMP-MON-ASE-2");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-2");

        assertEquals(1, items.size());
        assertNull(items.get(0).getCurrentApproverName());
        assertNull(items.get(0).getCurrentApproverId());
        assertEquals("CONFIRMED", items.get(0).getPeriodStatus());
    }

    // 功能：SUBMITTED 项目任务「当前审批人」应动态显示所属项目主 PD 姓名，而非硬编码「PD（待确认）」
    @Test
    void submittedShouldShowPrimaryPdAsApprover() {
        seedPeriod("PERIOD-MON-3", "ONGOING");
        seedEmployee("EMP-MON-PD-1");
        seedEmployee("EMP-MON-ASR-3");
        seedEmployee("EMP-MON-ASE-3");
        seedProjectTask("PERIOD-MON-3", "EMP-MON-ASR-3", "EMP-MON-ASE-3", "PROJ-MON-1", "STAGE-1");
        seedProject("PROJ-MON-1", "STAGE-1");
        seedRole("PD");
        seedPrimaryPd("PROJ-MON-1", "STAGE-1", "EMP-MON-PD-1");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-3");

        assertEquals(1, items.size());
        assertEquals("员工EMP-MON-PD-1", items.get(0).getCurrentApproverName());
        assertEquals("EMP-MON-PD-1", items.get(0).getCurrentApproverId());
    }

    // 功能：FUNCTIONAL 任务(无项目)SUBMITTED 仍回退「PD（待确认）」占位，无主 PD 可查时不崩
    @Test
    void submittedFunctionalShouldFallbackToPlaceholder() {
        seedPeriod("PERIOD-MON-4", "ONGOING");
        seedEmployee("EMP-MON-ASR-4");
        seedEmployee("EMP-MON-ASE-4");
        seedTask("PERIOD-MON-4", "EMP-MON-ASR-4", "EMP-MON-ASE-4");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-4");

        assertEquals(1, items.size());
        assertEquals("PD（待确认）", items.get(0).getCurrentApproverName());
        assertNull(items.get(0).getCurrentApproverId());
    }

    // ================= 辅助：种子数据 =================

    private void seedPeriod(String periodId, String status) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(periodId);
        period.setPeriodName("监控测试周期" + periodId);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 6, 30));
        period.setStatus(status);
        periodMapper.insert(period);
    }

    private void seedPeriodWithSubmittedAt(String periodId, String status, LocalDateTime submittedAt) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(periodId);
        period.setPeriodName("监控测试周期" + periodId);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 6, 30));
        period.setStatus(status);
        period.setCalibrationSubmittedAt(submittedAt);
        periodMapper.insert(period);
    }

    private void seedEmployee(String employeeId) {
        Employee e = new Employee();
        e.setEmployeeId(employeeId);
        e.setName("员工" + employeeId);
        e.setEmail(employeeId + "@test.com");
        e.setCategory("研发技术类");
        e.setPosition("整椅研发岗");
        e.setOrgName("信息部");
        e.setStatus("ACTIVE");
        employeeMapper.insert(e);
    }

    private void seedTask(String periodId, String assessorId, String assesseeId) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(periodId);
        task.setAssessorId(assessorId);
        task.setAssesseeId(assesseeId);
        task.setTaskType("FUNCTIONAL");
        task.setStatus("SUBMITTED");
        taskMapper.insert(task);
    }

    private void seedProjectTask(String periodId, String assessorId, String assesseeId,
                                 String projectCode, String projectStage) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(periodId);
        task.setAssessorId(assessorId);
        task.setAssesseeId(assesseeId);
        task.setTaskType("PROJECT");
        task.setProjectCode(projectCode);
        task.setProjectStage(projectStage);
        task.setStatus("SUBMITTED");
        taskMapper.insert(task);
    }

    private void seedPrimaryPd(String projectCode, String projectStage, String employeeId) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(projectStage);
        a.setProjectRoleCode("PD");
        a.setEmployeeId(employeeId);
        a.setIsPrimary(true);
        a.setDeleted(0);
        projectRoleAssignmentMapper.insert(a);
    }

    // 辅助：插入项目——满足 project_role_assignment (project_code, project_stage) 外键 fk_pra_project
    private void seedProject(String code, String stage) {
        Project project = new Project();
        project.setProjectCode(code);
        project.setProjectName("项目" + code);
        project.setProjectStage(stage);
        project.setStatus("ACTIVE");
        project.setStageConfirmed(false);
        projectMapper.insert(project);
    }

    // 辅助：插入项目角色——满足 project_role_assignment.project_role_code 外键 fk_pra_role
    private void seedRole(String roleCode) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode(roleCode);
        role.setRoleName("角色" + roleCode);
        projectRoleMapper.insert(role);
    }
}
