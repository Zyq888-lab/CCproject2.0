// 模块用途：TaskGeneratorService 单元测试——覆盖 launch 批量生成、Savepoint 容错、增量生成去重
// 依赖文件：TaskGeneratorService.java, TaskMapper.java, 各 Mapper
// 修改注意：Mockito 模拟全部 Mapper 依赖，不依赖真实数据库
package com.jifeng.assessment.task;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.notification.NotificationService;
import com.jifeng.assessment.participation.EmployeeProjectParticipation;
import com.jifeng.assessment.participation.ParticipationMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.position.PositionAssessorRoleConfig;
import com.jifeng.assessment.position.PositionAssessorRoleMapper;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.user.SysUserMapper;
import com.jifeng.assessment.user.UserRole;
import com.jifeng.assessment.user.UserRoleMapper;
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
    @Mock private FuncKpiMapper funcKpiMapper;
    @Mock private PeriodMapper periodMapper;
    @Mock private DiscrepancyLogMapper discrepancyLogMapper;
    @Mock private NotificationService notificationService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private UserRoleMapper userRoleMapper;

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
        // 通知相关依赖默认返回空——不干扰任务生成计数断言（lenient：部分用例提前返回不触发通知）
        lenient().when(sysUserMapper.selectList(any())).thenReturn(List.of());
        // 默认有职能 KPI 配置（selectCount>0）——保证历史用例的 FUNCTIONAL 任务计数不因新增守卫而改变
        lenient().when(funcKpiMapper.selectCount(any())).thenReturn(1L);
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

    // 辅助方法：构建 ONGOING 状态的考核周期（onParticipationApproved 的周期闸门需周期已发起才生成任务）
    private AssessmentPeriod ongoingPeriod(String periodId) {
        AssessmentPeriod p = new AssessmentPeriod();
        p.setPeriodId(periodId);
        p.setStatus("ONGOING");
        return p;
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
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1")));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1")));

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 1 PROJECT（ASSESSOR1 考核 EMP1）+ 1 FUNCTIONAL（LEADER1 考核 EMP1）
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
    // 2b. markPeriodOngoing: INIT→ONGOING 走原子 updateStatus（不再 select-then-updateById）
    // ========================================
    @Test
    void markPeriodOngoingShouldUseAtomicStatusFlip() {
        generatorService.markPeriodOngoing("PERIOD-001");

        verify(periodMapper).updateStatus("PERIOD-001", "INIT", "ONGOING");
        verify(periodMapper, never()).selectById(any());
        verify(periodMapper, never()).updateById(any());
    }

    // ========================================
    // 3. launchPeriod: Savepoint 容错 → 缺配置员工跳过，其余正常生成
    // ========================================
    @Test
    void launchShouldSkipEmployeeWithMissingConfig() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
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

        // EMP1 生成 2 条任务（1 PROJECT + 1 FUNCTIONAL），EMP2 缺配置跳过并写入 1 条差异
        assertEquals(2, result.taskCount());
        assertEquals(1, result.discrepancyCount());
        verify(discrepancyLogMapper, times(1)).insert(any(DiscrepancyLog.class));
    }

    // ========================================
    // 4. 岗位配置缺失 → 差异报告（与用例3同一场景，单独验证差异内容）
    // ========================================
    @Test
    void launchShouldWriteDiscrepancyForMissingConfig() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
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
    // 5. 降级策略 → 无考核人有上级 → 上级代考
    // ========================================
    @Test
    void launchShouldUseLeaderWhenNoAssessor() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1")));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        // 无考核人（角色分配为空）
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of());

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 上级代考 PROJECT + 上级 FUNCTIONAL
        assertEquals(2, result.taskCount());
        assertEquals(0, result.discrepancyCount());
        // 两条代考任务的考核人都应该是 LEADER1
        verify(taskMapper, times(2)).insertIgnore(argThat(t -> "LEADER1".equals(t.getAssessorId())));
    }

    // ========================================
    // 6. 降级策略 → 无考核人无上级 → 差异报告（记录 NO_ASSESSOR + NO_LEADER 两条差异）
    // ========================================
    @Test
    void launchShouldRecordDiscrepancyWhenNoAssessorAndNoLeader() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        // 无上级
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", null)));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of());

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 无考核人（角色分配空）且无上级 → PROJECT/FUNCTIONAL 均无任务；记录 NO_ASSESSOR + NO_LEADER 两条差异
        assertEquals(0, result.taskCount());
        assertEquals(2, result.discrepancyCount());
        verify(taskMapper, never()).insertIgnore(any());
        verify(discrepancyLogMapper, times(2)).insert(any(DiscrepancyLog.class));
    }

    // ========================================
    // 7. onParticipationApproved: 审批 → 增量生成 PROJECT 任务
    // ========================================
    @Test
    void onParticipationApprovedShouldGenerateProjectTasks() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(ongoingPeriod("PERIOD-001"));
        Employee emp = activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1");
        when(employeeMapper.selectById("EMP1")).thenReturn(emp);
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1")));

        EmployeeProjectParticipation participation = approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2");
        generatorService.onParticipationApproved(participation);

        // 1 PROJECT + 1 FUNCTIONAL
        verify(taskMapper, times(2)).insertIgnore(any(AssessmentTask.class));
    }

    // ========================================
    // 8. onParticipationApproved: FUNCTIONAL 去重 → INSERT ON CONFLICT DO NOTHING（幂等）
    // ========================================
    @Test
    void onParticipationApprovedShouldUseIdempotentInsert() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(ongoingPeriod("PERIOD-001"));
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
    // 9. createTask: 唯一约束冲突 → 静默跳过不抛异常（insertIgnore 返回 0 不影响流程）
    // ========================================
    @Test
    void insertIgnoreShouldNotThrowOnConflict() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(ongoingPeriod("PERIOD-001"));
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

    // ========================================
    // 10. onParticipationApproved: 周期未发起(INIT) → 闸门拦截，不生成任务
    // ========================================
    @Test
    void onParticipationApprovedShouldSkipWhenPeriodNotOngoing() {
        AssessmentPeriod init = new AssessmentPeriod();
        init.setPeriodId("PERIOD-001");
        init.setStatus("INIT");
        when(periodMapper.selectById("PERIOD-001")).thenReturn(init);

        EmployeeProjectParticipation participation = approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2");
        generatorService.onParticipationApproved(participation);

        // 闸门拦截：不查询员工、不生成任何任务、不发通知
        verify(employeeMapper, never()).selectById(any());
        verify(taskMapper, never()).insertIgnore(any(AssessmentTask.class));
    }

    // ========================================
    // 11. onParticipationApproved: 周期不存在 → 闸门拦截，不生成任务
    // ========================================
    @Test
    void onParticipationApprovedShouldSkipWhenPeriodMissing() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(null);

        EmployeeProjectParticipation participation = approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2");
        generatorService.onParticipationApproved(participation);

        verify(taskMapper, never()).insertIgnore(any(AssessmentTask.class));
    }

    // ========================================
    // 12. 职能 KPI 未配置 → 不生成空 FUNCTIONAL 任务（回归：避免空职能任务）
    // ========================================
    @Test
    void launchShouldSkipFunctionalWhenNoKpiConfig() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1")));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1")));
        // 无职能 KPI 配置 → selectCount 返回 0 → 跳过 FUNCTIONAL 任务
        when(funcKpiMapper.selectCount(any())).thenReturn(0L);

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 仅 1 条 PROJECT 任务（ASSESSOR1 考核 EMP1），FUNCTIONAL 被跳过
        assertEquals(1, result.taskCount());
        assertEquals(0, result.discrepancyCount());
        verify(taskMapper, times(1)).insertIgnore(argThat(t -> "PROJECT".equals(t.getTaskType())));
    }

    // ========================================
    // 12b. launch: 无参与记录 → 不生成 FUNCTIONAL 任务（回归：未参与项目的员工不被职能考核）
    // ========================================
    @Test
    void launchShouldSkipFunctionalWhenNoParticipation() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1")));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        // 关键：该员工本周期无 APPROVED 参与记录
        when(participationMapper.selectList(any())).thenReturn(List.of());

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 无参与记录 → 不生成任何任务（含 FUNCTIONAL），也不记差异
        assertEquals(0, result.taskCount());
        assertEquals(0, result.discrepancyCount());
        verify(taskMapper, never()).insertIgnore(any());
    }

    // ========================================
    // 12c. launch: 有参与记录 → 正常生成 FUNCTIONAL 任务（与 12b 配对，保证守卫不误伤参与者）
    // ========================================
    @Test
    void launchShouldGenerateFunctionalWhenHasParticipation() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1")));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1")));

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 有参与记录 → PROJECT + FUNCTIONAL 均生成
        assertEquals(2, result.taskCount());
        verify(taskMapper, times(1)).insertIgnore(argThat(t -> "FUNCTIONAL".equals(t.getTaskType())));
    }

    // ========================================
    // 13. onParticipationApproved: 同角色多人且无人标主 → 跳过该角色 + 记 NO_PRIMARY_ASSESSOR + 通知 admin
    // ========================================
    @Test
    void onParticipationApprovedShouldSkipRoleAndNotifyAdminWhenNoPrimary() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(ongoingPeriod("PERIOD-001"));
        Employee emp = activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1");
        when(employeeMapper.selectById("EMP1")).thenReturn(emp);
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        // 同角色两人均未标主
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1"),
                assignment("PRJ1", "P2", "PDL", "ASSESSOR2")));
        UserRole adminRole = new UserRole();
        adminRole.setUserId("ADMIN_U1");
        adminRole.setRoleType("ADMIN");
        when(userRoleMapper.selectList(any())).thenReturn(List.of(adminRole));

        EmployeeProjectParticipation participation = approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2");
        generatorService.onParticipationApproved(participation);

        // PROJECT 任务 0 条（角色被跳过），仅 FUNCTIONAL 1 条
        verify(taskMapper, never()).insertIgnore(argThat(t -> "PROJECT".equals(t.getTaskType())));
        verify(taskMapper, times(1)).insertIgnore(argThat(t -> "FUNCTIONAL".equals(t.getTaskType())));
        // 记一条 NO_PRIMARY_ASSESSOR 差异
        verify(discrepancyLogMapper, times(1)).insert(argThat(d ->
                "NO_PRIMARY_ASSESSOR".equals(d.getType()) && "EMP1".equals(d.getEmployeeId())));
        // 通知 admin
        verify(notificationService, times(1)).notifyBatch(argThat(list ->
                list.size() == 1 && "ADMIN_U1".equals(list.get(0).getRecipientId())));
    }

    // ========================================
    // 14. onParticipationApproved: 同角色多人恰好一个标主 → 只发主，不发给未标主者
    // ========================================
    @Test
    void onParticipationApprovedShouldOnlyAssignPrimaryAmongMultiple() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(ongoingPeriod("PERIOD-001"));
        Employee emp = activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1");
        when(employeeMapper.selectById("EMP1")).thenReturn(emp);
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        ProjectRoleAssignment primary = assignment("PRJ1", "P2", "PDL", "ASSESSOR1");
        primary.setIsPrimary(true);
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                primary,
                assignment("PRJ1", "P2", "PDL", "ASSESSOR2")));

        EmployeeProjectParticipation participation = approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2");
        generatorService.onParticipationApproved(participation);

        // PROJECT 任务只发给 ASSESSOR1（主），不发 ASSESSOR2
        verify(taskMapper, times(1)).insertIgnore(argThat(t ->
                "PROJECT".equals(t.getTaskType()) && "ASSESSOR1".equals(t.getAssessorId())));
        verify(taskMapper, never()).insertIgnore(argThat(t -> "ASSESSOR2".equals(t.getAssessorId())));
        // 无差异
        verify(discrepancyLogMapper, never()).insert(any());
    }

    // ========================================
    // 15. launch: 同角色多人无主且多员工参与 → 每个员工记一条差异，admin 通知按角色去重只发一次
    // ========================================
    @Test
    void launchShouldNotifyAdminOnceForNoPrimaryAcrossEmployees() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        // 两个员工参与同一项目同一阶段的同一无主角色
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1"),
                activeEmployee("EMP2", "研发技术类", "整椅研发岗", "LEADER2")));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        // participationMapper 对每个员工都返回同一条参与（employeeId 在循环内不再被使用）
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        // 同角色两人均未标主
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(
                assignment("PRJ1", "P2", "PDL", "ASSESSOR1"),
                assignment("PRJ1", "P2", "PDL", "ASSESSOR2")));
        UserRole adminRole = new UserRole();
        adminRole.setUserId("ADMIN_U1");
        adminRole.setRoleType("ADMIN");
        when(userRoleMapper.selectList(any())).thenReturn(List.of(adminRole));

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // PROJECT 任务 0 条（角色被跳过），仅 2 条 FUNCTIONAL（每员工一条）
        assertEquals(2, result.taskCount());
        verify(taskMapper, never()).insertIgnore(argThat(t -> "PROJECT".equals(t.getTaskType())));
        // 差异按员工记：2 条 NO_PRIMARY_ASSESSOR
        assertEquals(2, result.discrepancyCount());
        verify(discrepancyLogMapper, times(2)).insert(argThat(d ->
                "NO_PRIMARY_ASSESSOR".equals(d.getType())));
        // admin 通知按 (项目,阶段,角色) 去重，只发一次
        verify(notificationService, times(1)).notifyBatch(argThat(list ->
                list.size() == 1 && "ADMIN_U1".equals(list.get(0).getRecipientId())));
    }

    // ========================================
    // 16. launch: 同角色多人多主 → 只发第一个主，不报差异、不通知 admin（防御脏数据，仅告警）
    // ========================================
    @Test
    void launchShouldAssignOnlyFirstAmongMultiplePrimary() {
        when(periodMapper.selectById("PERIOD-001")).thenReturn(initPeriod);
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                activeEmployee("EMP1", "研发技术类", "整椅研发岗", "LEADER1")));
        when(positionConfigMapper.selectOne(any())).thenReturn(posConfig(1L, "研发技术类", "整椅研发岗"));
        when(assessorRoleMapper.selectList(any())).thenReturn(List.of(assessorRole(1L, 1L, "PDL")));
        when(participationMapper.selectList(any())).thenReturn(List.of(
                approvedParticipation("EMP1", "PERIOD-001", "PRJ1", "P2")));
        // 同角色两人均标主（历史脏数据，DB 部分唯一索引应已拦截；resolvePrimaryAssessors 只发第一个并 warn）
        ProjectRoleAssignment primary1 = assignment("PRJ1", "P2", "PDL", "ASSESSOR1");
        primary1.setIsPrimary(true);
        ProjectRoleAssignment primary2 = assignment("PRJ1", "P2", "PDL", "ASSESSOR2");
        primary2.setIsPrimary(true);
        when(roleAssignmentMapper.selectList(any())).thenReturn(List.of(primary1, primary2));

        TaskGeneratorService.LaunchResult result = generatorService.launch("PERIOD-001");

        // 只发第一个主 ASSESSOR1，不发 ASSESSOR2
        verify(taskMapper, times(1)).insertIgnore(argThat(t ->
                "PROJECT".equals(t.getTaskType()) && "ASSESSOR1".equals(t.getAssessorId())));
        verify(taskMapper, never()).insertIgnore(argThat(t -> "ASSESSOR2".equals(t.getAssessorId())));
        // 无差异、无 admin 通知（primaries 非空，未触发无主分支）
        assertEquals(0, result.discrepancyCount());
        verify(notificationService, never()).notifyBatch(any());
    }
}
