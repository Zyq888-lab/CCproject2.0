// 模块用途：ParticipationService 审批权限单元测试——覆盖主PM/非主PM/PD/ADMIN/跨阶段五种审批场景
// 依赖文件：ParticipationService.java, EmployeeProjectParticipation.java, ProjectRoleAssignment.java
// 修改注意：测试用 H2 内存库，每个用例独立，不依赖执行顺序；参与记录关联 project/employee/assessment_period 外键
package com.jifeng.assessment.participation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
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
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
class ParticipationServiceTest {

    @Autowired
    private ParticipationService participationService;

    @Autowired
    private ParticipationMapper participationMapper;

    @Autowired
    private ProjectRoleMapper projectRoleMapper;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private ProjectRoleAssignmentMapper roleAssignmentMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private PeriodMapper periodMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // 功能：主 PM 审批通过——project_role_code='PM' AND is_primary=true AND project_stage=参与记录阶段，状态置为 APPROVED
    @Test
    void primaryPmShouldApprove() {
        seedRole("PM");
        seedEmployee("EMP_PART_1");
        seedEmployee("EMP_PM_1");
        seedUser("U_PM_1", "pm_primary", "EMP_PM_1");
        seedProject("PRJ_A", "P2");
        seedPeriod("PERIOD_1");
        seedAssignment("PRJ_A", "P2", "PM", "EMP_PM_1", true);
        Long id = seedParticipation("EMP_PART_1", "PERIOD_1", "PRJ_A", "P2");

        auth("pm_primary", "PM");

        EmployeeProjectParticipation result = participationService.approve(id, true, null, "同意");

        assertEquals("APPROVED", result.getStatus());
        assertEquals("pm_primary", result.getApprovedBy());
    }

    // 功能：非主 PM 被 403——该 PM 在项目上 is_primary=false，无权审批
    @Test
    void nonPrimaryPmShouldBeForbidden() {
        seedRole("PM");
        seedEmployee("EMP_PART_2");
        seedEmployee("EMP_PM_2");
        seedUser("U_PM_2", "pm_secondary", "EMP_PM_2");
        seedProject("PRJ_B", "P2");
        seedPeriod("PERIOD_2");
        seedAssignment("PRJ_B", "P2", "PM", "EMP_PM_2", false);
        Long id = seedParticipation("EMP_PART_2", "PERIOD_2", "PRJ_B", "P2");

        auth("pm_secondary", "PM");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.approve(id, true, null, null));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("主 PM"));
    }

    // 功能：PD 被 403——即使为主 PD（is_primary=true AND role='PD'），也不具备审批资格
    @Test
    void pdShouldBeForbidden() {
        seedRole("PD");
        seedEmployee("EMP_PART_3");
        seedEmployee("EMP_PD_1");
        seedUser("U_PD_1", "pd_primary", "EMP_PD_1");
        seedProject("PRJ_C", "P2");
        seedPeriod("PERIOD_3");
        seedAssignment("PRJ_C", "P2", "PD", "EMP_PD_1", true);
        Long id = seedParticipation("EMP_PART_3", "PERIOD_3", "PRJ_C", "P2");

        auth("pd_primary", "PD");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.approve(id, true, null, null));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("主 PM"));
    }

    // 功能：ADMIN 放行——不受主 PM 校验约束，可直接审批通过
    @Test
    void adminShouldApprove() {
        seedRole("PM");
        seedEmployee("EMP_PART_4");
        seedEmployee("EMP_ADMIN_1");
        seedUser("U_ADMIN_1", "admin_approve", "EMP_ADMIN_1");
        seedProject("PRJ_D", "P2");
        seedPeriod("PERIOD_4");
        Long id = seedParticipation("EMP_PART_4", "PERIOD_4", "PRJ_D", "P2");

        auth("admin_approve", "ADMIN");

        EmployeeProjectParticipation result = participationService.approve(id, true, null, null);

        assertEquals("APPROVED", result.getStatus());
    }

    // 功能：跨阶段被 403——PM 是项目 P2 阶段的主 PM，但参与记录属于 P3 阶段，无权审批
    @Test
    void crossStageShouldBeForbidden() {
        seedRole("PM");
        seedEmployee("EMP_PART_5");
        seedEmployee("EMP_PM_5");
        seedUser("U_PM_5", "pm_cross", "EMP_PM_5");
        seedProject("PRJ_E", "P2");
        seedProject("PRJ_E", "P3");
        seedPeriod("PERIOD_5");
        seedAssignment("PRJ_E", "P2", "PM", "EMP_PM_5", true);
        Long id = seedParticipation("EMP_PART_5", "PERIOD_5", "PRJ_E", "P3");

        auth("pm_cross", "PM");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.approve(id, true, null, null));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("主 PM"));
    }

    // 功能：列表返回当前审批人——PENDING 记录填充该项目该阶段主 PM 的工号（project_role_code='PM' AND is_primary=true）
    @Test
    void listShouldReturnCurrentApproverForPending() {
        seedRole("PM");
        seedEmployee("EMP_PART_9");
        seedEmployee("EMP_PM_9");
        seedEmployee("EMP_ADMIN_9");
        seedUser("U_ADMIN_9", "admin_list_9", "EMP_ADMIN_9");
        seedProject("PRJ_F", "P2");
        seedPeriod("PERIOD_9");
        seedAssignment("PRJ_F", "P2", "PM", "EMP_PM_9", true);
        Long id = seedParticipation("EMP_PART_9", "PERIOD_9", "PRJ_F", "P2");

        auth("admin_list_9", "ADMIN");

        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_9", null, null);

        EmployeeProjectParticipation row = result.getList().stream()
                .filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
        assertEquals("EMP_PM_9", row.getCurrentApproverEmployeeId());
    }

    // 功能：CALIBRATING 期拒绝填写参与——assertParticipatable 冻结校准/确认/关闭期参与写操作
    @Test
    void createShouldBeRejectedWhenCalibrating() {
        seedPeriodWithStatus("PERIOD_10", "CALIBRATING");
        auth("admin_cal", "ADMIN");

        ProjectParticipationItem item = new ProjectParticipationItem();
        item.setProjectCode("PRJ_X");
        item.setProjectStage("P2");
        item.setParticipationRate(new BigDecimal("100"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.create("EMP_X", "PERIOD_10", List.of(item)));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校准"));
    }

    // 功能：CALIBRATING 期拒绝审批——ADMIN 亦不可在校准期审批参与记录
    @Test
    void approveShouldBeRejectedWhenCalibrating() {
        seedEmployee("EMP_PART_10");
        seedProject("PRJ_X", "P2");
        seedPeriodWithStatus("PERIOD_10", "CALIBRATING");
        Long id = seedParticipation("EMP_PART_10", "PERIOD_10", "PRJ_X", "P2");

        auth("admin_cal", "ADMIN");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.approve(id, true, null, null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校准"));
    }

    // 功能：CALIBRATING 期拒绝重新提交——已拒绝记录在校准期不可重置为 PENDING
    @Test
    void resubmitShouldBeRejectedWhenCalibrating() {
        seedEmployee("EMP_PART_11");
        seedProject("PRJ_Y", "P2");
        seedPeriodWithStatus("PERIOD_11", "CALIBRATING");
        Long id = seedParticipation("EMP_PART_11", "PERIOD_11", "PRJ_Y", "P2");

        auth("admin_cal", "ADMIN");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.resubmit(id, new BigDecimal("80")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校准"));
    }

    // 功能：INIT 期允许填写参与——4a 曾误收紧为 ONGOING-only，回归验证「新建周期后即可填参与」
    @Test
    void createShouldSucceedWhenInit() {
        seedRole("PM");
        seedEmployee("EMP_INIT_1");
        seedUser("U_INIT_1", "emp_init_1", "EMP_INIT_1");
        seedProject("PRJ_I", "P2");
        seedPeriodWithStatus("PERIOD_INIT_1", "INIT");
        seedAssignment("PRJ_I", "P2", "PM", "EMP_INIT_1", false);

        auth("emp_init_1", "员工");

        ProjectParticipationItem item = new ProjectParticipationItem();
        item.setProjectCode("PRJ_I");
        item.setProjectStage("P2");
        item.setParticipationRate(new BigDecimal("100"));

        List<EmployeeProjectParticipation> result =
                participationService.create(null, "PERIOD_INIT_1", List.of(item));

        assertEquals(1, result.size());
        assertEquals("PENDING", result.get(0).getStatus());
    }

    // 功能：INIT 期允许审批参与——审批通过后不立即生成任务，交由 launch 统一生成
    @Test
    void approveShouldSucceedWhenInit() {
        seedRole("PM");
        seedEmployee("EMP_INIT_2");
        seedEmployee("EMP_PM_INIT");
        seedUser("U_INIT_2", "emp_init_2", "EMP_INIT_2");
        seedUser("U_PM_INIT", "pm_init", "EMP_PM_INIT");
        seedProject("PRJ_J", "P2");
        seedPeriodWithStatus("PERIOD_INIT_2", "INIT");
        seedAssignment("PRJ_J", "P2", "PM", "EMP_PM_INIT", true);
        Long id = seedParticipation("EMP_INIT_2", "PERIOD_INIT_2", "PRJ_J", "P2");

        auth("pm_init", "PM");

        EmployeeProjectParticipation result = participationService.approve(id, true, null, null);
        assertEquals("APPROVED", result.getStatus());
    }

    // 功能：INIT 期允许重新提交——被拒参与在发起前可重置为 PENDING
    @Test
    void resubmitShouldSucceedWhenInit() {
        seedRole("PM");
        seedEmployee("EMP_INIT_3");
        seedUser("U_INIT_3", "emp_init_3", "EMP_INIT_3");
        seedProject("PRJ_K", "P2");
        seedPeriodWithStatus("PERIOD_INIT_3", "INIT");
        Long id = seedParticipation("EMP_INIT_3", "PERIOD_INIT_3", "PRJ_K", "P2");
        EmployeeProjectParticipation p = participationMapper.selectById(id);
        p.setStatus("REJECTED");
        participationMapper.updateById(p);

        auth("emp_init_3", "员工");

        EmployeeProjectParticipation result = participationService.resubmit(id, new BigDecimal("80"));
        assertEquals("PENDING", result.getStatus());
    }

    // 功能：多角色（员工+PM）用户取并集视角——既能看到自己作为员工提交的参与记录（非主负责项目），
    //   也能看到自己主负责项目上的他人参与记录（PM 审批视角），二者互不吞并
    @Test
    void employeeWithPmRoleShouldSeeOwnAndManagedParticipations() {
        seedRole("PM");
        seedEmployee("EMP_MULTI");
        seedEmployee("EMP_OTHER");
        seedUser("U_MULTI", "multi_user", "EMP_MULTI");
        seedProject("PRJ_OWN", "P2");
        seedProject("PRJ_MANAGED", "P2");
        seedPeriod("PERIOD_MULTI");
        seedAssignment("PRJ_MANAGED", "P2", "PM", "EMP_MULTI", true);
        Long ownId = seedParticipation("EMP_MULTI", "PERIOD_MULTI", "PRJ_OWN", "P2");
        Long managedId = seedParticipation("EMP_OTHER", "PERIOD_MULTI", "PRJ_MANAGED", "P2");

        // 同时授予「员工」与「PM」两个角色
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("multi_user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_员工"),
                                new SimpleGrantedAuthority("ROLE_PM"))));

        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_MULTI", null, null);

        List<Long> ids = result.getList().stream().map(EmployeeProjectParticipation::getId).toList();
        assertTrue(ids.contains(ownId), "员工+PM 用户应能看到自己作为员工提交的参与记录");
        assertTrue(ids.contains(managedId), "员工+PM 用户应能看到自己主负责项目上的他人参与记录");
    }

    // 功能：评估人不再授予项目可见性——仅持有「评估人」角色(无员工/PM/PD)时，即便在项目上有角色分配，
    //   也不应看到他人参与记录（旧逻辑会经 listAssignedProjectCodes 泄露该项目的全部参与记录）
    @Test
    void assessorShouldNotSeeOthersParticipations() {
        seedRole("PM");
        seedEmployee("EMP_ASSESSOR");
        seedEmployee("EMP_OTHER2");
        seedUser("U_ASSESSOR", "assessor_user", "EMP_ASSESSOR");
        seedProject("PRJ_ASSESS", "P2");
        seedPeriod("PERIOD_ASSESS");
        // 评估人在该项目上有角色分配（主 PM）——旧逻辑据此授予项目可见性，导致泄露他人记录
        seedAssignment("PRJ_ASSESS", "P2", "PM", "EMP_ASSESSOR", true);
        seedParticipation("EMP_OTHER2", "PERIOD_ASSESS", "PRJ_ASSESS", "P2");

        auth("assessor_user", "评估人");

        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_ASSESS", null, null);

        assertTrue(result.getList().isEmpty(), "仅评估人角色的用户不应看到他人参与记录");
    }

    // 功能：同一员工同一周期同一项目不同阶段可分别提交参与（问题2——存在性校验按阶段区分）
    @Test
    void createShouldAllowSameProjectDifferentStages() {
        seedRole("PM");
        seedEmployee("EMP_STAGE_1");
        seedUser("U_STAGE_1", "emp_stage_1", "EMP_STAGE_1");
        seedProject("PRJ_S", "P2");
        seedProject("PRJ_S", "P3");
        seedPeriodWithStatus("PERIOD_STAGE_1", "INIT");
        seedAssignment("PRJ_S", "P2", "PM", "EMP_STAGE_1", false);
        seedAssignment("PRJ_S", "P3", "PM", "EMP_STAGE_1", false);

        auth("emp_stage_1", "员工");

        ProjectParticipationItem p2 = new ProjectParticipationItem();
        p2.setProjectCode("PRJ_S");
        p2.setProjectStage("P2");
        p2.setParticipationRate(new BigDecimal("100"));
        participationService.create(null, "PERIOD_STAGE_1", List.of(p2));

        ProjectParticipationItem p3 = new ProjectParticipationItem();
        p3.setProjectCode("PRJ_S");
        p3.setProjectStage("P3");
        p3.setParticipationRate(new BigDecimal("100"));
        // 第二次 create 成功（关键回归：不再被误判为「同项目重复」而 409）
        participationService.create(null, "PERIOD_STAGE_1", List.of(p3));

        // 同 (员工,周期,项目) 下两条不同阶段记录并存
        List<EmployeeProjectParticipation> all = participationMapper.selectList(
                new LambdaQueryWrapper<EmployeeProjectParticipation>()
                        .eq(EmployeeProjectParticipation::getEmployeeId, "EMP_STAGE_1")
                        .eq(EmployeeProjectParticipation::getPeriodId, "PERIOD_STAGE_1")
                        .eq(EmployeeProjectParticipation::getProjectCode, "PRJ_S"));
        assertEquals(2, all.size());
        assertEquals(List.of("P2", "P3"),
                all.stream().map(EmployeeProjectParticipation::getProjectStage).sorted().toList());
    }

    // 功能：同一员工同一周期同一项目同一阶段重复提交被拒（问题2——同阶段仍按唯一约束拦截）
    @Test
    void createShouldRejectSameProjectSameStage() {
        seedRole("PM");
        seedEmployee("EMP_STAGE_2");
        seedUser("U_STAGE_2", "emp_stage_2", "EMP_STAGE_2");
        seedProject("PRJ_S2", "P2");
        seedPeriodWithStatus("PERIOD_STAGE_2", "INIT");
        seedAssignment("PRJ_S2", "P2", "PM", "EMP_STAGE_2", false);

        auth("emp_stage_2", "员工");

        ProjectParticipationItem item = new ProjectParticipationItem();
        item.setProjectCode("PRJ_S2");
        item.setProjectStage("P2");
        item.setParticipationRate(new BigDecimal("100"));
        participationService.create(null, "PERIOD_STAGE_2", List.of(item));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> participationService.create(null, "PERIOD_STAGE_2", List.of(item)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    // 功能：主 PM 的待审批列表只含本人主负责项目的参与记录——PM-A 主负责 PRJ_A，不应看到 PM-B 主负责 PRJ_B 的参与记录
    @Test
    void pendingListShouldExcludeOtherPmsProject() {
        seedRole("PM");
        seedEmployee("EMP_PM_A");
        seedEmployee("EMP_PM_B");
        seedEmployee("EMP_PART_A");
        seedEmployee("EMP_PART_B");
        seedUser("U_PM_A", "pm_a", "EMP_PM_A");
        seedUser("U_PM_B", "pm_b", "EMP_PM_B");
        seedProject("PRJ_A", "P2");
        seedProject("PRJ_B", "P2");
        seedPeriod("PERIOD_PM_FILTER");
        seedAssignment("PRJ_A", "P2", "PM", "EMP_PM_A", true);
        seedAssignment("PRJ_B", "P2", "PM", "EMP_PM_B", true);
        seedParticipation("EMP_PART_A", "PERIOD_PM_FILTER", "PRJ_A", "P2");
        seedParticipation("EMP_PART_B", "PERIOD_PM_FILTER", "PRJ_B", "P2");

        auth("pm_a", "PM");

        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_PM_FILTER", "PENDING", null);

        List<String> codes = result.getList().stream()
                .map(EmployeeProjectParticipation::getProjectCode).toList();
        assertTrue(codes.contains("PRJ_A"), "PM-A 待审批列表应含自己主负责项目 PRJ_A 的参与记录");
        assertFalse(codes.contains("PRJ_B"), "PM-A 待审批列表不应含 PM-B 主负责项目 PRJ_B 的参与记录");
    }

    // 功能：审批视角(scope=approval)不含本人提交——PM+员工 用户提交的本人参与（审批权属他项目主 PM）不应出现在其待审批列表
    //   （回归：祝工 zhu/E004 在自己待审批列表看到本人提交的 P007/P3 记录，其审批权属 E002/lizong）
    @Test
    void approvalScopeShouldExcludeOwnSubmission() {
        seedRole("PM");
        seedEmployee("EMP_PM_OWN");
        seedEmployee("EMP_OTHER_PM");
        seedEmployee("EMP_OTHER_SUB");
        seedUser("U_PM_OWN", "pm_own", "EMP_PM_OWN");
        seedUser("U_OTHER_PM", "other_pm", "EMP_OTHER_PM");
        seedProject("PRJ_MINE", "P2");
        seedProject("PRJ_THEIRS", "P2");
        seedPeriod("PERIOD_OWN");
        seedAssignment("PRJ_MINE", "P2", "PM", "EMP_PM_OWN", true);
        seedAssignment("PRJ_THEIRS", "P2", "PM", "EMP_OTHER_PM", true);
        Long managedId = seedParticipation("EMP_OTHER_SUB", "PERIOD_OWN", "PRJ_MINE", "P2");
        Long ownId = seedParticipation("EMP_PM_OWN", "PERIOD_OWN", "PRJ_THEIRS", "P2");

        // 同时授予「PM」与「员工」两个角色（与 zhu/E004 一致）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_own", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"),
                                new SimpleGrantedAuthority("ROLE_员工"))));

        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_OWN", "PENDING", null, "approval");

        List<Long> ids = result.getList().stream().map(EmployeeProjectParticipation::getId).toList();
        assertTrue(ids.contains(managedId), "审批视角应含主 PM 负责项目 PRJ_MINE 的他人提交");
        assertFalse(ids.contains(ownId), "审批视角不应含本人提交的 PRJ_THEIRS 记录（审批权属他人）");
    }

    // 功能：审批视角(scope=approval)不含主 PD 可见性——PM+PD 多角色用户仅见主 PM 负责项目，仅主 PD 负责项目不泄露进审批列表
    @Test
    void approvalScopeShouldExcludePdOnlyScopedForPmUser() {
        seedRole("PM");
        seedRole("PD");
        seedEmployee("EMP_PM_PD");
        seedEmployee("EMP_OTHER_A");
        seedEmployee("EMP_OTHER_B");
        seedUser("U_PM_PD", "pm_pd_user", "EMP_PM_PD");
        seedProject("PRJ_PM", "P2");
        seedProject("PRJ_PD", "P2");
        seedPeriod("PERIOD_PM_PD");
        seedAssignment("PRJ_PM", "P2", "PM", "EMP_PM_PD", true);
        seedAssignment("PRJ_PD", "P2", "PD", "EMP_PM_PD", true);
        Long pmScoped = seedParticipation("EMP_OTHER_A", "PERIOD_PM_PD", "PRJ_PM", "P2");
        Long pdScoped = seedParticipation("EMP_OTHER_B", "PERIOD_PM_PD", "PRJ_PD", "P2");

        // 同时授予「PM」与「PD」两个角色（与 E002/lizong 一致）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_pd_user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"),
                                new SimpleGrantedAuthority("ROLE_PD"))));

        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_PM_PD", "PENDING", null, "approval");

        List<Long> ids = result.getList().stream().map(EmployeeProjectParticipation::getId).toList();
        assertTrue(ids.contains(pmScoped), "审批视角应含主 PM 负责项目 PRJ_PM 的参与记录");
        assertFalse(ids.contains(pdScoped), "审批视角不应含仅主 PD 负责项目 PRJ_PD 的参与记录");
    }

    // 同一员工同时申请两个项目时，各主 PM 只能在待审批列表看到自己负责的项目；
    // 即使两名 PM 同时是对方项目的主 PD，也不能把「可查看」记录混入「可审批」列表。
    @Test
    void approvalScopeShouldSplitTwoProjectApplicationsBetweenTheirPrimaryPms() {
        seedRole("PM");
        seedRole("PD");
        seedEmployee("EMP_APPLICANT_MULTI");
        seedEmployee("EMP_PM_001");
        seedEmployee("EMP_PM_002");
        seedUser("U_PM_001", "pm_001", "EMP_PM_001");
        seedUser("U_PM_002", "pm_002", "EMP_PM_002");
        seedProject("PRJ_001", "P1");
        seedProject("PRJ_002", "P2");
        seedPeriod("PERIOD_TWO_PROJECTS");
        seedAssignment("PRJ_001", "P1", "PM", "EMP_PM_001", true);
        seedAssignment("PRJ_002", "P2", "PM", "EMP_PM_002", true);
        seedAssignment("PRJ_001", "P1", "PD", "EMP_PM_002", true);
        seedAssignment("PRJ_002", "P2", "PD", "EMP_PM_001", true);
        Long id001 = seedParticipation("EMP_APPLICANT_MULTI", "PERIOD_TWO_PROJECTS", "PRJ_001", "P1");
        Long id002 = seedParticipation("EMP_APPLICANT_MULTI", "PERIOD_TWO_PROJECTS", "PRJ_002", "P2");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_001", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"), new SimpleGrantedAuthority("ROLE_PD"))));
        PageResult<EmployeeProjectParticipation> pm001 = participationService.listParticipations(
                new PageQuery(), "PERIOD_TWO_PROJECTS", "PENDING", null, "approval");
        assertEquals(List.of(id001), pm001.getList().stream().map(EmployeeProjectParticipation::getId).toList());
        assertEquals(1, pm001.getTotal());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pm_002", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PM"), new SimpleGrantedAuthority("ROLE_PD"))));
        PageResult<EmployeeProjectParticipation> pm002 = participationService.listParticipations(
                new PageQuery(), "PERIOD_TWO_PROJECTS", "PENDING", null, "approval");
        assertEquals(List.of(id002), pm002.getList().stream().map(EmployeeProjectParticipation::getId).toList());
        assertEquals(1, pm002.getTotal());
    }

    @Test
    void approvalScopeShouldRequirePmRoleEvenWhenAssignmentExists() {
        seedRole("PM");
        seedEmployee("EMP_ASSIGNMENT_ONLY");
        seedEmployee("EMP_APPLICANT_ROLE");
        seedUser("U_ASSIGNMENT_ONLY", "assignment_only", "EMP_ASSIGNMENT_ONLY");
        seedProject("PRJ_ROLE", "P1");
        seedPeriod("PERIOD_ROLE");
        seedAssignment("PRJ_ROLE", "P1", "PM", "EMP_ASSIGNMENT_ONLY", true);
        seedParticipation("EMP_APPLICANT_ROLE", "PERIOD_ROLE", "PRJ_ROLE", "P1");

        auth("assignment_only", "员工");
        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_ROLE", "PENDING", null, "approval");
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    @Test
    void approvalScopeShouldOnlyIncludePendingRecords() {
        seedRole("PM");
        seedEmployee("EMP_PM_STATUS");
        seedEmployee("EMP_APPLICANT_STATUS");
        seedUser("U_PM_STATUS", "pm_status", "EMP_PM_STATUS");
        seedProject("PRJ_STATUS", "P1");
        seedPeriod("PERIOD_STATUS");
        seedAssignment("PRJ_STATUS", "P1", "PM", "EMP_PM_STATUS", true);
        Long id = seedParticipation("EMP_APPLICANT_STATUS", "PERIOD_STATUS", "PRJ_STATUS", "P1");
        EmployeeProjectParticipation processed = participationMapper.selectById(id);
        processed.setStatus("APPROVED");
        participationMapper.updateById(processed);

        auth("pm_status", "PM");
        PageResult<EmployeeProjectParticipation> result = participationService.listParticipations(
                new PageQuery(), "PERIOD_STATUS", null, null, "approval");
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    // 辅助：插入项目角色
    private void seedRole(String roleCode) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode(roleCode);
        role.setRoleName("角色" + roleCode);
        projectRoleMapper.insert(role);
    }

    // 辅助：插入员工
    private void seedEmployee(String employeeId) {
        Employee emp = new Employee();
        emp.setEmployeeId(employeeId);
        emp.setName("员工" + employeeId);
        emp.setEmail(employeeId + "@test.com");
        emp.setCategory("管理类");
        emp.setPosition("项目经理");
        emp.setOrgName("信息部");
        emp.setStatus("ACTIVE");
        employeeMapper.insert(emp);
    }

    // 辅助：插入系统用户（绑定员工）
    private void seedUser(String userId, String username, String employeeId) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUsername(username);
        user.setPasswordHash("test-hash");
        user.setEmployeeId(employeeId);
        user.setEnabled(true);
        sysUserMapper.insert(user);
    }

    // 辅助：插入项目（复合主键 project_code+project_stage）
    private void seedProject(String code, String stage) {
        Project project = new Project();
        project.setProjectCode(code);
        project.setProjectName("项目" + code);
        project.setProjectStage(stage);
        project.setStatus("ACTIVE");
        project.setStageConfirmed(false);
        projectMapper.insert(project);
    }

    // 辅助：插入考核周期（ONGOING 状态——approve/create/resubmit 在 INIT/ONGOING 均可；
    //   缺岗位配置使审批通过后的增量任务生成 no-op，故测试不会触发任务生成）
    private void seedPeriod(String periodId) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(periodId);
        period.setPeriodName("周期" + periodId);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 12, 31));
        period.setStatus("ONGOING");
        periodMapper.insert(period);
    }

    // 辅助：插入指定状态的考核周期——用于验证非 ONGOING 状态拒绝参与写操作
    private void seedPeriodWithStatus(String periodId, String status) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(periodId);
        period.setPeriodName("周期" + periodId);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 12, 31));
        period.setStatus(status);
        periodMapper.insert(period);
    }

    // 辅助：插入角色分配（显式指定是否为主）
    private void seedAssignment(String projectCode, String stage, String roleCode, String employeeId, boolean isPrimary) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode(roleCode);
        a.setEmployeeId(employeeId);
        a.setIsPrimary(isPrimary);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：插入参与记录（PENDING 状态），返回自增主键
    private Long seedParticipation(String employeeId, String periodId, String projectCode, String projectStage) {
        EmployeeProjectParticipation p = new EmployeeProjectParticipation();
        p.setEmployeeId(employeeId);
        p.setPeriodId(periodId);
        p.setProjectCode(projectCode);
        p.setProjectStage(projectStage);
        p.setParticipationRate(new BigDecimal("100"));
        p.setStatus("PENDING");
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        participationMapper.insert(p);
        return p.getId();
    }

    // 辅助：设置当前登录用户及其角色
    private void auth(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
