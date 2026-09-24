// 模块用途：PeriodMonitorService 单元测试——当前审批人按周期状态优先映射
// 依赖文件：PeriodMonitorService.java, PeriodMapper.java, EmployeeMapper.java, TaskMapper.java
// 修改注意：@SpringBootTest + @Transactional（测试库 PostgreSQL，每个用例独立回滚）；
//   无鉴权上下文 → getPrimaryRole() 返回空串，不走 PM 项目过滤，聚合全周期任务
package com.jifeng.assessment.monitor;

import com.jifeng.assessment.calibration.CalibrationSubmission;
import com.jifeng.assessment.calibration.CalibrationSubmissionMapper;
import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
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

import java.math.BigDecimal;
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
    @Autowired private CalibrationSubmissionMapper calibrationSubmissionMapper;
    @Autowired private ProjectConfirmationMapper projectConfirmationMapper;
    @Autowired private FuncKpiMapper funcKpiMapper;

    // 功能：CALIBRATING 且 PD 尚未提交校准(calibrationSubmittedAt 为空)时「当前审批人」应为「PD（待校准）」——
    //   旧映射在进入校准后立即显示「总裁（待确认）」，误判了提交状态
    @Test
    void calibratingWithoutSubmissionShouldShowPdPending() {
        seedPeriod("PERIOD-MON-1", "CALIBRATING");
        seedEmployee("EMP-MON-ASR-1");
        seedEmployee("EMP-MON-ASE-1");
        seedFuncKpi();
        seedTask("PERIOD-MON-1", "EMP-MON-ASR-1", "EMP-MON-ASE-1");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-1");

        assertEquals(1, items.size());
        assertEquals("PD（待校准）", items.get(0).getCurrentApproverName());
        assertNull(items.get(0).getCurrentApproverId());
        assertEquals("CALIBRATING", items.get(0).getPeriodStatus());
    }

    // 功能：CALIBRATING 且该项目已提交校准(submitted_at 非空)但无主总裁时，「当前审批人」应回落「总裁（待确认）」
    @Test
    void calibratingWithSubmissionShouldShowPresidentAsApprover() {
        seedPeriod("PERIOD-MON-5", "CALIBRATING");
        seedEmployee("EMP-MON-ASR-5");
        seedEmployee("EMP-MON-ASE-5");
        seedProjectTask("PERIOD-MON-5", "EMP-MON-ASR-5", "EMP-MON-ASE-5", "PROJ-MON-5", "STAGE-1");
        seedProject("PROJ-MON-5", "STAGE-1");
        seedCalibrationSubmission("PERIOD-MON-5", "PROJ-MON-5", "STAGE-1", "EMP-MON-ASE-5");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-5");

        assertEquals(1, items.size());
        assertEquals("总裁（待确认）", items.get(0).getCurrentApproverName());
        assertNull(items.get(0).getCurrentApproverId());
        assertEquals("CALIBRATING", items.get(0).getPeriodStatus());
        assertEquals(Boolean.TRUE, items.get(0).getCalibrationSubmitted());
    }

    // 功能：CALIBRATING 且该项目已提交校准 + PROJECT 任务带主总裁时，「当前审批人」应显示具体主总裁姓名 +「（待确认）」
    @Test
    void calibratingWithSubmissionProjectTaskShouldShowPrimaryPresidentName() {
        seedPeriod("PERIOD-MON-7", "CALIBRATING");
        seedEmployee("EMP-MON-PRES-7");
        seedEmployee("EMP-MON-ASR-7");
        seedEmployee("EMP-MON-ASE-7");
        seedProjectTask("PERIOD-MON-7", "EMP-MON-ASR-7", "EMP-MON-ASE-7", "PROJ-MON-3", "STAGE-1");
        seedProject("PROJ-MON-3", "STAGE-1");
        // PRESIDENT 项目角色已在 V27 种子，直接插分配即可
        seedPrimaryPresident("PROJ-MON-3", "STAGE-1", "EMP-MON-PRES-7");
        seedCalibrationSubmission("PERIOD-MON-7", "PROJ-MON-3", "STAGE-1", "EMP-MON-PRES-7");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-7");

        assertEquals(1, items.size());
        assertEquals("员工EMP-MON-PRES-7（待确认）", items.get(0).getCurrentApproverName());
        assertEquals("EMP-MON-PRES-7", items.get(0).getCurrentApproverId());
        assertEquals("CALIBRATING", items.get(0).getPeriodStatus());
        assertEquals(Boolean.TRUE, items.get(0).getCalibrationSubmitted());
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

    // 功能：CALIBRATING 期项目级校准提交——同周期两项目一个已提交一个未提交，逐行区分
    //   「总裁姓名（待确认）」/「主PD姓名（待校准）」（回归：部分提交时周期级 flag 无法区分）
    @Test
    void calibratingPartialSubmissionShouldDistinguishPerProject() {
        seedPeriod("PERIOD-MON-8", "CALIBRATING");
        seedEmployee("EMP-MON-PD-8");
        seedEmployee("EMP-MON-PRES-8");
        seedEmployee("EMP-MON-ASR-8");
        seedEmployee("EMP-MON-ASE-8");
        // 项目 A：已提交校准（有主总裁）
        seedProject("PROJ-MON-A", "STAGE-1");
        seedProjectTask("PERIOD-MON-8", "EMP-MON-ASR-8", "EMP-MON-ASE-8", "PROJ-MON-A", "STAGE-1");
        seedPrimaryPresident("PROJ-MON-A", "STAGE-1", "EMP-MON-PRES-8");
        seedCalibrationSubmission("PERIOD-MON-8", "PROJ-MON-A", "STAGE-1", "EMP-MON-PRES-8");
        // 项目 B：未提交校准（有主 PD）
        seedProject("PROJ-MON-B", "STAGE-1");
        seedProjectTask("PERIOD-MON-8", "EMP-MON-ASR-8", "EMP-MON-ASE-8", "PROJ-MON-B", "STAGE-1");
        seedRole("PD");
        seedPrimaryPd("PROJ-MON-B", "STAGE-1", "EMP-MON-PD-8");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-8");

        assertEquals(2, items.size());
        PeriodMonitorItem submitted = items.stream()
                .filter(i -> "PROJ-MON-A".equals(i.getProjectCode())).findFirst().orElseThrow();
        PeriodMonitorItem unsubmitted = items.stream()
                .filter(i -> "PROJ-MON-B".equals(i.getProjectCode())).findFirst().orElseThrow();

        assertEquals("员工EMP-MON-PRES-8（待确认）", submitted.getCurrentApproverName());
        assertEquals("EMP-MON-PRES-8", submitted.getCurrentApproverId());
        assertEquals(Boolean.TRUE, submitted.getCalibrationSubmitted());

        assertEquals("员工EMP-MON-PD-8（待校准）", unsubmitted.getCurrentApproverName());
        assertEquals("EMP-MON-PD-8", unsubmitted.getCurrentApproverId());
        assertEquals(Boolean.FALSE, unsubmitted.getCalibrationSubmitted());

        // 周期级汇总：2 个项目涉及，1 个已提交（顶部 Alert 数据源）
        assertEquals(1, submitted.getSubmittedProjectCount());
        assertEquals(2, submitted.getTotalProjectCount());
    }

    // 功能：CALIBRATING 期总裁确认状态逐行区分——项目A APPROVED、项目B PENDING，
    //   断言两行 confirmationStatus / currentApproverName 区分（回归：监控看板只读 task 状态未读 project_confirmation）
    @Test
    void calibratingApprovedVsPendingShouldDistinguishConfirmationStatus() {
        seedPeriod("PERIOD-MON-9", "CALIBRATING");
        seedEmployee("EMP-MON-PRES-9");
        seedEmployee("EMP-MON-ASR-9");
        seedEmployee("EMP-MON-ASE-9");
        // 项目 A：已提交校准 + 总裁已确认（APPROVED → 无当前审批人）
        seedProject("PROJ-MON-C", "STAGE-1");
        seedProjectTask("PERIOD-MON-9", "EMP-MON-ASR-9", "EMP-MON-ASE-9", "PROJ-MON-C", "STAGE-1");
        seedPrimaryPresident("PROJ-MON-C", "STAGE-1", "EMP-MON-PRES-9");
        seedCalibrationSubmission("PERIOD-MON-9", "PROJ-MON-C", "STAGE-1", "EMP-MON-PRES-9");
        seedProjectConfirmation("PERIOD-MON-9", "PROJ-MON-C", "EMP-MON-ASE-9", "APPROVED");
        // 项目 B：已提交校准 + 总裁待确认（PENDING → 主总裁待确认）
        seedProject("PROJ-MON-D", "STAGE-1");
        seedProjectTask("PERIOD-MON-9", "EMP-MON-ASR-9", "EMP-MON-ASE-9", "PROJ-MON-D", "STAGE-1");
        seedPrimaryPresident("PROJ-MON-D", "STAGE-1", "EMP-MON-PRES-9");
        seedCalibrationSubmission("PERIOD-MON-9", "PROJ-MON-D", "STAGE-1", "EMP-MON-PRES-9");
        seedProjectConfirmation("PERIOD-MON-9", "PROJ-MON-D", "EMP-MON-ASE-9", "PENDING");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-9");

        assertEquals(2, items.size());
        PeriodMonitorItem approved = items.stream()
                .filter(i -> "PROJ-MON-C".equals(i.getProjectCode())).findFirst().orElseThrow();
        PeriodMonitorItem pending = items.stream()
                .filter(i -> "PROJ-MON-D".equals(i.getProjectCode())).findFirst().orElseThrow();

        // 项目 A：APPROVED → 审批人为空，确认状态 APPROVED
        assertEquals("APPROVED", approved.getConfirmationStatus());
        assertNull(approved.getCurrentApproverName());
        assertNull(approved.getCurrentApproverId());
        // 项目 B：PENDING → 主总裁待确认
        assertEquals("PENDING", pending.getConfirmationStatus());
        assertEquals("员工EMP-MON-PRES-9（待确认）", pending.getCurrentApproverName());
        assertEquals("EMP-MON-PRES-9", pending.getCurrentApproverId());

        // 周期级汇总：2 个项目需确认，1 个已全部确认（顶部 Alert 数据源）
        assertEquals(1, approved.getConfirmedProjectCount());
        assertEquals(2, approved.getConfirmationProjectCount());
    }

    // 功能：CONFIRMED 终态「当前审批人」应为空——即使任务仍为 SUBMITTED，终态不再有审批人
    @Test
    void confirmedShouldShowNoApprover() {
        seedPeriod("PERIOD-MON-2", "CONFIRMED");
        seedEmployee("EMP-MON-ASR-2");
        seedEmployee("EMP-MON-ASE-2");
        seedFuncKpi();
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
        seedFuncKpi();
        seedTask("PERIOD-MON-4", "EMP-MON-ASR-4", "EMP-MON-ASE-4");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-4");

        assertEquals(1, items.size());
        assertEquals("PD（待确认）", items.get(0).getCurrentApproverName());
        assertNull(items.get(0).getCurrentApproverId());
    }

    // 功能：监控看板状态与节点文案按任务真实状态映射——PENDING/IN_PROGRESS/SUBMITTED 各映射为
    //   待评分/评分中/已提交，节点文案同步映射为待评估人评分/评估人评分中/待确认
    @Test
    void statusAndNodeLabelShouldMapFromTaskStatus() {
        seedPeriod("PERIOD-MON-STATUS", "ONGOING");
        seedEmployee("EMP-MON-ASR-S");
        // uk_task_unique 含 (period_id, assessor_id, assessee_id, task_type)，同评估人+被考核人只能有一条 FUNCTIONAL 任务，
        //   故用三个不同被考核人各建一条任务来覆盖三种状态
        seedEmployee("EMP-MON-ASE-S1");
        seedEmployee("EMP-MON-ASE-S2");
        seedEmployee("EMP-MON-ASE-S3");
        seedFuncKpi();
        seedTaskWithStatus("PERIOD-MON-STATUS", "EMP-MON-ASR-S", "EMP-MON-ASE-S1", "PENDING");
        seedTaskWithStatus("PERIOD-MON-STATUS", "EMP-MON-ASR-S", "EMP-MON-ASE-S2", "IN_PROGRESS");
        seedTaskWithStatus("PERIOD-MON-STATUS", "EMP-MON-ASR-S", "EMP-MON-ASE-S3", "SUBMITTED");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-STATUS");

        assertEquals(3, items.size());
        PeriodMonitorItem pending = items.stream()
                .filter(i -> "待评分".equals(i.getStatus())).findFirst().orElseThrow();
        PeriodMonitorItem inProgress = items.stream()
                .filter(i -> "评分中".equals(i.getStatus())).findFirst().orElseThrow();
        PeriodMonitorItem submitted = items.stream()
                .filter(i -> "已提交".equals(i.getStatus())).findFirst().orElseThrow();

        assertEquals("待评估人评分", pending.getNodeLabel());
        assertEquals("评估人评分中", inProgress.getNodeLabel());
        assertEquals("待确认", submitted.getNodeLabel());
    }

    // 功能：CALIBRATING 期状态按校准提交状态映射——同周期两个项目一个已提交一个未提交，
    //   断言两行 status 分别为「已校准」「待校准」（回归：状态列仅按任务状态映射，忽略校准维度）
    @Test
    void calibratingStatusShouldReflectCalibrationSubmission() {
        seedPeriod("PERIOD-MON-CAL", "CALIBRATING");
        seedEmployee("EMP-MON-PRES-C");
        seedEmployee("EMP-MON-PD-C");
        seedEmployee("EMP-MON-ASR-C");
        seedEmployee("EMP-MON-ASE-C");
        // 项目 A：已提交校准（有主总裁）
        seedProject("PROJ-CAL-A", "STAGE-1");
        seedProjectTask("PERIOD-MON-CAL", "EMP-MON-ASR-C", "EMP-MON-ASE-C", "PROJ-CAL-A", "STAGE-1");
        seedPrimaryPresident("PROJ-CAL-A", "STAGE-1", "EMP-MON-PRES-C");
        seedCalibrationSubmission("PERIOD-MON-CAL", "PROJ-CAL-A", "STAGE-1", "EMP-MON-PRES-C");
        // 项目 B：未提交校准（有主 PD）
        seedProject("PROJ-CAL-B", "STAGE-1");
        seedProjectTask("PERIOD-MON-CAL", "EMP-MON-ASR-C", "EMP-MON-ASE-C", "PROJ-CAL-B", "STAGE-1");
        seedRole("PD");
        seedPrimaryPd("PROJ-CAL-B", "STAGE-1", "EMP-MON-PD-C");

        List<PeriodMonitorItem> items = periodMonitorService.monitor("PERIOD-MON-CAL");

        assertEquals(2, items.size());
        PeriodMonitorItem submitted = items.stream()
                .filter(i -> "PROJ-CAL-A".equals(i.getProjectCode())).findFirst().orElseThrow();
        PeriodMonitorItem unsubmitted = items.stream()
                .filter(i -> "PROJ-CAL-B".equals(i.getProjectCode())).findFirst().orElseThrow();

        assertEquals("已校准", submitted.getStatus());
        assertEquals("待校准", unsubmitted.getStatus());
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

    private void seedCalibrationSubmission(String periodId, String projectCode, String projectStage, String submittedBy) {
        CalibrationSubmission s = new CalibrationSubmission();
        s.setPeriodId(periodId);
        s.setProjectCode(projectCode);
        s.setProjectStage(projectStage);
        s.setSubmittedByEmployeeId(submittedBy);
        s.setSubmittedAt(LocalDateTime.now());
        s.setDeleted(0);
        calibrationSubmissionMapper.insert(s);
    }

    private void seedProjectConfirmation(String periodId, String projectCode, String assesseeId, String status) {
        ProjectConfirmation c = new ProjectConfirmation();
        c.setPeriodId(periodId);
        c.setProjectCode(projectCode);
        c.setAssesseeId(assesseeId);
        c.setStatus(status);
        c.setReturnCount(0);
        c.setDeleted(0);
        projectConfirmationMapper.insert(c);
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

    // 辅助：插入职能 KPI 配置——FUNCTIONAL 任务需按被考核人 category+position 解析出 ≥1 指标，
    //   才能通过监控看板「无职能 KPI 不展示」过滤（Issue 2：没有职能 KPI 就过滤空条目）
    private void seedFuncKpi() {
        FuncKpiConfig kpi = new FuncKpiConfig();
        kpi.setCategory("研发技术类");
        kpi.setPosition("整椅研发岗");
        kpi.setKpiName("职能KPI");
        kpi.setEvaluationCriteria("评价标准");
        kpi.setWeight(new BigDecimal("1.0000"));
        kpi.setSortOrder(1);
        kpi.setIsActive(true);
        funcKpiMapper.insert(kpi);
    }

    private void seedTask(String periodId, String assessorId, String assesseeId) {
        seedTaskWithStatus(periodId, assessorId, assesseeId, "SUBMITTED");
    }

    private void seedTaskWithStatus(String periodId, String assessorId, String assesseeId, String status) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(periodId);
        task.setAssessorId(assessorId);
        task.setAssesseeId(assesseeId);
        task.setTaskType("FUNCTIONAL");
        task.setStatus(status);
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

    private void seedPrimaryPresident(String projectCode, String projectStage, String employeeId) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(projectStage);
        a.setProjectRoleCode("PRESIDENT");
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
