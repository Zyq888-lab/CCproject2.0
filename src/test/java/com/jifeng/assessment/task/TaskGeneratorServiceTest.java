// 模块用途：TaskGeneratorService 单元测试——覆盖 launch 批量生成、Savepoint 容错、增量生成去重
// 依赖文件：TaskGeneratorService.java, TaskMapper.java, 各 Mapper
// 修改注意：Mockito 模拟全部 Mapper 依赖，不依赖真实数据库
package com.jifeng.assessment.task;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.participation.EmployeeProjectParticipation;
import com.jifeng.assessment.participation.ParticipationMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.position.PositionAssessorRoleConfig;
import com.jifeng.assessment.position.PositionAssessorRoleMapper;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskGeneratorServiceTest {

    @Mock private TaskMapper taskMapper;
    @Mock private ParticipationMapper participationMapper;
    @Mock private PositionConfigMapper positionConfigMapper;
    @Mock private PositionAssessorRoleMapper assessorRoleMapper;
    @Mock private ProjectRoleAssignmentMapper roleAssignmentMapper;
    @Mock private EmployeeMapper employeeMapper;
    @Mock private PeriodMapper periodMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private DiscrepancyLogMapper discrepancyLogMapper;

    @InjectMocks
    private TaskGeneratorService generatorService;

    private AssessmentPeriod initPeriod;

    @BeforeEach
    void setUp() {
        initPeriod = new AssessmentPeriod();
        initPeriod.setPeriodId("PERIOD-001");
        initPeriod.setStatus("INIT");
        // 自注入代理 self 字段在纯 Mockito 测试中不注入，手动指向当前实例（markPeriodOngoing 直接走真实方法）
        ReflectionTestUtils.setField(generatorService, "self", generatorService);
    }

    // 辅助方法：构建已确认阶段的 ACTIVE 项目
    private Project confirmedProject(String code, String stage) {
        Project p = new Project();
        p.setProjectCode(code);
        p.setProjectStage(stage);
        p.setStatus("ACTIVE");
        p.setStageConfirmed(true);
        return p;
    }

    // 辅助方法：构建 ACTIVE 员工
    private Employee activeEmployee(String id, String category, String position, String leaderId) {
        Employee e = new Employee();
        e.setEmployeeId(id);
        e.setName("员工" + id);
        e.setCategory(category);
        e.setPosition(position);
        e.setDirectLeaderId(leaderId);
        e.setStatus("ACTIVE");
        return e;
    }

    // 辅助方法：构建岗位配置
    private PositionAssessmentConfig posConfig(Long id, String category, String position) {
        PositionAssessmentConfig c = new PositionAssessmentConfig();
        c.setId(id);
        c.setCategory(category);
        c.setPosition(position);
        return c;
    }

    // 辅助方法：构建考核人角色
    private PositionAssessorRoleConfig assessorRole(Long id, Long configId, String roleCode) {
        PositionAssessorRoleConfig r = new PositionAssessorRoleConfig();
        r.setId(id);
        r.setPositionConfigId(configId);
        r.setRoleCode(roleCode);
        return r;
    }

    // 辅助方法：构建已审批的项目参与记录
    private EmployeeProjectParticipation approvedParticipation(String empId, String periodId,
                                                               String code, String stage) {
        EmployeeProjectParticipation p = new EmployeeProjectParticipation();
        p.setEmployeeId(empId);
        p.setPeriodId(periodId);
        p.setProjectCode(code);
        p.setProjectStage(stage);
        p.setStatus("APPROVED");
        return p;
    }

    // 辅助方法：构建角色分配人员
    private ProjectRoleAssignment assignment(String code, String stage, String roleCode, String empId) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(code);
        a.setProjectStage(stage);
        a.setProjectRoleCode(roleCode);
        a.setEmployeeId(empId);
        return a;
    }

    // ========================================
    // 1. launchPeriod: 正常发起 → 任务生成数量正确
    // ========================================
    @Test
    void launchShouldGenerateCorrectTaskCount() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(projectMapper.selectList(any())).thenReturn(List.of(confirmedProject("PRJ1", "P2")));
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1")));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1")));

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 1 个 PROJECT 任务（ASSESSOR1 考核 EMP1）+ 1 个 FUNCTIONAL 任务（LEADER1 考核 EMP1）
        assertEquals(2, result.taskCount());
        assertEquals(0, result.discrepancyCount());
        verify(taskMapper, times(2)).insertIgnore(any(AssessmentTask.class));
    }

    // ========================================
    // 2. launchPeriod: 前置校验 → 非INIT周期拒绝
    // ========================================
    @Test
    void launchShouldRejectNonInitPeriod() {
        AssessmentPeriod ongoing = new AssessmentPeriod();
        ongoing.setPeriodId("PERIOD-001");
        ongoing.setStatus("ONGOING");
        when(periodMapper.selectById("PERIOD-001")).thenReturn(ongoing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> generatorService.launch("PERIOD-001"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("仅未开始"));
        verify(taskMapper, never()).insertIgnore(any());
    }

    // ========================================
    // 3. launchPeriod: 前置校验 → 未确认阶段项目拒绝
    // ========================================
    @Test
    void launchShouldRejectUnconfirmedProject() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        Project unconfirmed = confirmedProject("PRJ1", "P2");
        unconfirmed.setStageConfirmed(false);
        when(projectMapper.selectList(any())).thenReturn(List.of(unconfirmed));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> generatorService.launch("PERIOD-001"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("尚未确认阶段"));
        verify(taskMapper, never()).insertIgnore(any());
    }

    // ========================================
    // 4. launchPeriod: Savepoint 容错 → 缺配置员工跳过，其余正常生成
    // ========================================
    @Test
    void launchShouldSkipEmployeeWithMissingConfig() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(projectMapper.selectList(any())).thenReturn(List.of(confirmedProject("PRJ1", "P2")));
        // 两个员工：EMP1 有配置，EMP2 缺配置
        Employee emp1 = activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1");
        Employee emp2 = activeEmployee("EMP2", "无配置类", "无配置岗", "LEADER2");
        when(employeeMapper.selectList(any())).thenReturn(List.of(emp1, emp2));
        // EMP1 有配置
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1")));

        // 关键：EMP2 缺配置时，positionConfigMapper.selectOne 需要返回 null
        // 用 thenReturn 分段：第一次(EMP1)返回配置，第二次(EMP2)返回 null
        when(positionConfigMapper.selectOne(any()))
                .thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"))
                .thenReturn(null);

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // EMP1 生成 2 条任务，EMP2 缺配置跳过并写入 1 条差异
        assertEquals(2, result.taskCount());
        assertEquals(1, result.discrepancyCount());
        verify(discrepancyLogMapper, times(1)).insert(any(DiscrepancyLog.class));
    }

    // ========================================
    // 5. 岗位配置缺失 → 差异报告（与用例4同一场景，单独验证差异内容）
    // ========================================
    @Test
    void launchShouldWriteDiscrepancyForMissingConfig() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(projectMapper.selectList(any())).thenReturn(List.of(confirmedProject("PRJ1", "P2")));
        Employee emp = activeEmployee("EMP1", "无配置类", "无配置岗", "LEADER1");
        when(employeeMapper.selectList(any())).thenReturn(List.of(emp));
        when(positionConfigMapper.selectOne(any())).thenReturn(null);

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        assertEquals(0, result.taskCount());
        assertEquals(1, result.discrepancyCount());
        verify(discrepancyLogMapper, times(1)).insert(argThat(d ->
                "NO_POSITION_CONFIG".equals(d.getType()) && "EMP1".equals(d.getEmployeeId())));
    }

    // ========================================
    // 6. 降级策略 → 无考核人有上级 → 上级代考
    // ========================================
    @Test
    void launchShouldUseLeaderWhenNoAssessor() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(projectMapper.selectList(any())).thenReturn(List.of(confirmedProject("PRJ1", "P2")));
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1")));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        // 无考核人（角色分配为空）
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of());

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 上级代考 PROJECT 任务 + 上级 FUNCTIONAL 任务
        assertEquals(2, result.taskCount());
        assertEquals(0, result.discrepancyCount());
        // 两条任务的考核人都应该是 LEADER1
        verify(taskMapper, times(2)).insertIgnore(argThat(t -> "LEADER1".equals(t.getAssessorId())));
    }

    // ========================================
    // 7. 降级策略 → 无考核人无上级 → 差异报告（记录 NO_ASSESSOR + NO_LEADER 两条差异）
    // ========================================
    @Test
    void launchShouldRecordDiscrepancyWhenNoAssessorAndNoLeader() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(projectMapper.selectList(any())).thenReturn(List.of(confirmedProject("PRJ1", "P2")));
        // 无上级
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", null)));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of());

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 无考核人（角色分配空）且无上级 → 无任务，但记录 NO_ASSESSOR + NO_LEADER 两条差异
        assertEquals(0, result.taskCount());
        assertEquals(2, result.discrepancyCount());
        verify(taskMapper, never()).insertIgnore(any());
        verify(discrepancyLogMapper, times(2)).insert(any(DiscrepancyLog.class));
    }

    // ========================================
    // 8. onParticipationApproved: 审批 → 增量生成 PROJECT 任务
    // ========================================
    @Test
    void onParticipationApprovedShouldGenerateProjectTasks() {
        Employee emp = activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1");
        when(employeeMapper.selectById("EMP1")).thenReturn(emp);
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1")));

        EmployeeProjectParticipation participation = approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2");
        generatorService.onParticipationApproved(participation);

        // 1 个 PROJECT 任务 + 1 个 FUNCTIONAL 任务
        verify(taskMapper, times(2)).insertIgnore(any(AssessmentTask.class));
    }

    // ========================================
    // 9. onParticipationApproved: FUNCTIONAL 去重 → INSERT ON CONFLICT DO NOTHING（幂等）
    // ========================================
    @Test
    void onParticipationApprovedShouldUseIdempotentInsert() {
        Employee emp = activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1");
        when(employeeMapper.selectById("EMP1")).thenReturn(emp);
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        // 无考核人，走上级代考
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of());

        EmployeeProjectParticipation participation = approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2");
        generatorService.onParticipationApproved(participation);

        // 必须调用 insertIgnore（幂等插入），而不是普通 insert
        verify(taskMapper, atLeastOnce()).insertIgnore(any(AssessmentTask.class));
        verify(taskMapper, never()).insert(any(AssessmentTask.class));
    }

    // ========================================
    // 10. createTask: 唯一约束冲突 → 静默跳过不抛异常（insertIgnore 返回 0 不影响流程）
    // ========================================
    @Test
    void insertIgnoreShouldNotThrowOnConflict() {
        Employee emp = activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1");
        when(employeeMapper.selectById("EMP1")).thenReturn(emp);
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1")));
        // insertIgnore 模拟 ON CONFLICT DO NOTHING：返回 0（未插入），但不抛异常
        when(taskMapper.insertIgnore(any(AssessmentTask.class))).thenReturn(0);

        EmployeeProjectParticipation participation = approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2");
        // 不应抛异常
        assertDoesNotThrow(() -> generatorService.onParticipationApproved(participation));
    }
}
