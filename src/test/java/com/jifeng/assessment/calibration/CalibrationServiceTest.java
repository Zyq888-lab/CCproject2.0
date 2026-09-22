// 模块用途：CalibrationService 单元测试——校准矩阵离群计算 + 行内改分乐观锁与审计
// 依赖文件：CalibrationService.java, AssessmentResultMapper.java, ScoreAdjustmentMapper.java, 各 Mapper
// 修改注意：@SpringBootTest + @Transactional（测试库 PostgreSQL，每个用例独立回滚）
package com.jifeng.assessment.calibration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import com.jifeng.assessment.result.ScoreAdjustment;
import com.jifeng.assessment.result.ScoreAdjustmentMapper;
import com.jifeng.assessment.result.ScoreKpiAdjustment;
import com.jifeng.assessment.result.ScoreKpiAdjustmentMapper;
import com.jifeng.assessment.score.AssessmentScore;
import com.jifeng.assessment.score.ScoreMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
class CalibrationServiceTest {

    @Autowired private CalibrationService calibrationService;
    @Autowired private AssessmentResultMapper resultMapper;
    @Autowired private ScoreAdjustmentMapper adjustmentMapper;
    @Autowired private PeriodMapper periodMapper;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private ProjectMapper projectMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private ProjectRoleAssignmentMapper roleAssignmentMapper;
    @Autowired private ProjectConfirmationMapper projectConfirmationMapper;
    @Autowired private ProjectRoleMapper projectRoleMapper;
    @Autowired private CalibrationSubmissionMapper calibrationSubmissionMapper;
    @Autowired private ProjectKpiMapper projectKpiMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private ScoreKpiAdjustmentMapper kpiAdjustmentMapper;
    @Autowired private AssessmentProjectSubtotalMapper projectSubtotalMapper;

    private static final String PERIOD = "PERIOD-CAL";
    private static final String PROJECT = "PRJ1";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // 功能：矩阵按项目归组并正确标记离群——小计按 assessment_score 重算后 [4.0,4.1,4.2,2.0] 中 2.0 偏离均值 >1σ，判 LOW 离群且置顶
    @Test
    void matrixShouldFlagLowOutlierAndSortFirst() {
        seedPeriod("CALIBRATING");
        seedProject();
        seedEmployee("ASSESSOR1");
        // 单一项目 KPI（权重 1.0），员工得分即项目任务小计
        Long kpiId = seedProjectKpi("PD", "P2", "1.0000");

        seedResult("EMP_A", "4.0000");
        seedResult("EMP_B", "4.1000");
        seedResult("EMP_C", "4.2000");
        seedResult("EMP_D", "2.0000");

        seedProjectTaskWithScore("EMP_A", kpiId, "4.0");
        seedProjectTaskWithScore("EMP_B", kpiId, "4.1");
        seedProjectTaskWithScore("EMP_C", kpiId, "4.2");
        seedProjectTaskWithScore("EMP_D", kpiId, "2.0");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        assertEquals(1, matrix.getSummary().size());
        CalibrationMatrixResponse.GroupSummary gs = matrix.getSummary().get(0);
        assertEquals("project:" + PROJECT + "|P2", gs.getKey());
        assertEquals(4, gs.getCount());
        assertEquals(1, gs.getOutlierCount());

        assertEquals(4, matrix.getRows().size());
        // 离群置顶：2.0 员工排第一，且方向 LOW
        CalibrationMatrixResponse.Row first = matrix.getRows().get(0);
        assertEquals("EMP_D", first.getAssesseeId());
        assertTrue(first.isOutlier());
        assertEquals("LOW", first.getDirection());
        // 非离群行不被误标
        assertTrue(matrix.getRows().stream().filter(r -> !r.getAssesseeId().equals("EMP_D")).noneMatch(CalibrationMatrixResponse.Row::isOutlier));
    }

    // 功能：仅职能任务的员工归「职能」组，不混入项目组
    @Test
    void matrixShouldGroupFunctionalOnlyEmployeeSeparately() {
        seedPeriod("CALIBRATING");
        seedProject();
        seedEmployee("ASSESSOR1");
        seedResult("EMP_P", "4.0000");
        seedResult("EMP_F", "3.5000");
        seedProjectTask("EMP_P");
        seedFunctionalTask("EMP_F");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        assertEquals(2, matrix.getSummary().size());
        CalibrationMatrixResponse.Row funcRow = matrix.getRows().stream()
                .filter(r -> r.getAssesseeId().equals("EMP_F")).findFirst().orElseThrow();
        assertEquals("functional", funcRow.getGroupKey());
        assertEquals("职能考核", funcRow.getGroupLabel());
    }

    // 功能：半提交员工只出现在未提交暗行，不与正常结果行重复（后端严格完整性语义下行列互斥）
    @Test
    void matrixShouldNotDuplicatePartialSubmission() {
        seedPeriod("CALIBRATING");
        seedProject();
        seedEmployee("ASSESSOR1");
        // 全提交员工：有结果行 + SUBMITTED 项目任务 → 出现在正常行
        seedResult("EMP_FULL", "4.0000");
        seedProjectTask("EMP_FULL");
        // 半提交员工：无结果行，仅一条 IN_PROGRESS 任务 → 归入未提交暗行
        seedEmployee("EMP_HALF");
        seedPendingTask("EMP_HALF");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        assertEquals(1, matrix.getRows().size());
        assertEquals("EMP_FULL", matrix.getRows().get(0).getAssesseeId());
        assertEquals(1, matrix.getUnsubmitted().size());
        assertEquals("EMP_HALF", matrix.getUnsubmitted().get(0).getAssesseeId());
        assertEquals(1, matrix.getUnsubmittedCount());
    }

    // 功能：改分写 adjusted_score + 追加审计行（无鉴权上下文中 adjusted_by 回退 system）
    @Test
    void adjustShouldUpdateScoreAndWriteAudit() {
        seedPeriod("CALIBRATING");
        seedResult("EMP_A", "3.7000");

        calibrationService.adjust(PERIOD, "EMP_A", new BigDecimal("4.5000"), "评估人评分尺度偏差");

        AssessmentResult result = resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, PERIOD)
                .eq(AssessmentResult::getAssesseeId, "EMP_A"));
        assertEquals(0, new BigDecimal("4.5000").compareTo(result.getAdjustedScore()));
        assertEquals(0, new BigDecimal("3.7000").compareTo(result.getOriginalScore()));

        ScoreAdjustment audit = adjustmentMapper.selectOne(new LambdaQueryWrapper<ScoreAdjustment>()
                .eq(ScoreAdjustment::getAssessmentResultId, result.getId()));
        assertNotNull(audit);
        assertEquals(0, new BigDecimal("3.7000").compareTo(audit.getOldScore()));
        assertEquals(0, new BigDecimal("4.5000").compareTo(audit.getNewScore()));
        assertEquals("评估人评分尺度偏差", audit.getReason());
        assertEquals("system", audit.getAdjustedBy());
    }

    // 功能：非 CALIBRATING 周期拒绝改分——400 业务异常
    @Test
    void adjustShouldRejectNonCalibratingPeriod() {
        seedPeriod("ONGOING");
        seedResult("EMP_A", "3.7000");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                calibrationService.adjust(PERIOD, "EMP_A", new BigDecimal("4.5000"), "评估人评分尺度偏差"));
        assertEquals(400, ex.getCode());
    }

    // 功能：总裁调用校准矩阵只返回自己作为主总裁的项目的评分明细，其它项目数据不泄露
    @Test
    void presidentShouldOnlySeeOwnProjectsInMatrix() {
        seedPeriod("CALIBRATING");
        seedEmployee("ASSESSOR1");
        seedEmployee("EMP_PRES_OWNER");
        seedEmployee("EMP_PRES_OTHER");
        seedUser("U_PRES_OWNER", "pres_cal", "EMP_PRES_OWNER");

        seedProject("PRJ_OWN", "P2");
        seedProject("PRJ_OTHER", "P2");
        seedPresidentAssignment("PRJ_OWN", "P2", "EMP_PRES_OWNER");
        seedPresidentAssignment("PRJ_OTHER", "P2", "EMP_PRES_OTHER");

        // 自有项目员工 EMP_A；他人项目员工 EMP_B
        seedResult("EMP_A", "4.0000");
        seedResult("EMP_B", "3.0000");
        seedProjectTask("EMP_A", "PRJ_OWN", "P2");
        seedProjectTask("EMP_B", "PRJ_OTHER", "P2");

        auth("pres_cal", "总裁");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        List<String> assessees = matrix.getRows().stream()
                .map(CalibrationMatrixResponse.Row::getAssesseeId).toList();
        assertEquals(1, assessees.size());
        assertTrue(assessees.contains("EMP_A"));
        assertFalse(assessees.contains("EMP_B"));
        // 汇总带仅含自有项目分组
        assertEquals(1, matrix.getSummary().size());
        assertEquals("project:PRJ_OWN|P2", matrix.getSummary().get(0).getKey());
    }

    // 功能：校准矩阵回填总裁确认状态与退回意见——被退回人员行带 RETURNED 状态 + returnReason（问题2）
    @Test
    void matrixShouldReturnConfirmationStatusAndReturnReason() {
        seedPeriod("CALIBRATING");
        seedProject();
        seedEmployee("ASSESSOR1");
        seedResult("EMP_A", "4.0000");
        seedProjectTask("EMP_A");
        seedConfirmation("EMP_A", "RETURNED", "结果有误");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        CalibrationMatrixResponse.Row row = matrix.getRows().stream()
                .filter(r -> "EMP_A".equals(r.getAssesseeId())).findFirst().orElseThrow();
        assertEquals("RETURNED", row.getConfirmationStatus());
        assertEquals("结果有误", row.getReturnReason());
    }

    // 功能：同一项目编码不同阶段分属不同分组，且各行按阶段显示正确项目名（问题1——复合键分组+名称）
    @Test
    void matrixShouldSplitSameCodeByStageAndShowCorrectName() {
        seedPeriod("CALIBRATING");
        seedEmployee("ASSESSOR1");
        // 同一 code 不同 stage 且项目名不同（模拟 UAT 数据漂移：同 code 多名）
        seedProject("PRJ_M", "P2", "测试项目2.0");
        seedProject("PRJ_M", "P3", "测试项目2.3");
        seedResult("EMP_P2", "4.0000");
        seedResult("EMP_P3", "4.5000");
        seedProjectTask("EMP_P2", "PRJ_M", "P2");
        seedProjectTask("EMP_P3", "PRJ_M", "P3");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        // 同 code 不同 stage 拆成两组
        assertEquals(2, matrix.getSummary().size());
        CalibrationMatrixResponse.GroupSummary gP2 = matrix.getSummary().stream()
                .filter(g -> "project:PRJ_M|P2".equals(g.getKey())).findFirst().orElseThrow();
        CalibrationMatrixResponse.GroupSummary gP3 = matrix.getSummary().stream()
                .filter(g -> "project:PRJ_M|P3".equals(g.getKey())).findFirst().orElseThrow();
        assertEquals("测试项目2.0·P2", gP2.getLabel());
        assertEquals("测试项目2.3·P3", gP3.getLabel());

        // 各行按自身阶段显示正确项目名
        CalibrationMatrixResponse.Row rowP2 = matrix.getRows().stream()
                .filter(r -> "EMP_P2".equals(r.getAssesseeId())).findFirst().orElseThrow();
        CalibrationMatrixResponse.Row rowP3 = matrix.getRows().stream()
                .filter(r -> "EMP_P3".equals(r.getAssesseeId())).findFirst().orElseThrow();
        assertEquals("project:PRJ_M|P2", rowP2.getGroupKey());
        assertEquals("测试项目2.0·P2", rowP2.getGroupLabel());
        assertEquals("project:PRJ_M|P3", rowP3.getGroupKey());
        assertEquals("测试项目2.3·P3", rowP3.getGroupLabel());
    }

    // 功能：总裁视角下，多项目员工只展开自己负责的项目组，不泄露他人项目
    //   （问题1补充——分组归属：E004 同时参与 P006/P007，总裁负责 P007，只应看到 P007「测试项目2.3」）
    @Test
    void presidentMatrixShouldGroupMultiProjectEmployeeToOwnedProject() {
        seedPeriod("CALIBRATING");
        seedEmployee("ASSESSOR1");
        seedEmployee("EMP_PRES_OWNER");
        seedUser("U_PRES_OWNER2", "pres_cal2", "EMP_PRES_OWNER");

        // 总裁负责 PRJ_OWN（测试项目2.3），不负责 PRJ_OTHER（测试项目2.0）
        seedProject("PRJ_OWN", "P2", "测试项目2.3");
        seedProject("PRJ_OTHER", "P2", "测试项目2.0");
        seedPresidentAssignment("PRJ_OWN", "P2", "EMP_PRES_OWNER");

        // 员工 EMP_A 同时参与两个项目；PRJ_OTHER 任务先插入（id 更小），PRJ_OWN 后插入（id 更大但属总裁）
        seedResult("EMP_A", "4.0000");
        seedProjectTask("EMP_A", "PRJ_OTHER", "P2");
        seedProjectTask("EMP_A", "PRJ_OWN", "P2");

        auth("pres_cal2", "总裁");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        // 总裁只应看到自己负责的项目分组：EMP_A 归到 PRJ_OWN 组，而非 PRJ_OTHER
        assertEquals(1, matrix.getSummary().size());
        CalibrationMatrixResponse.GroupSummary gs = matrix.getSummary().get(0);
        assertEquals("project:PRJ_OWN|P2", gs.getKey());
        assertEquals("测试项目2.3·P2", gs.getLabel());
        assertEquals(1, matrix.getRows().size());
        assertEquals("project:PRJ_OWN|P2", matrix.getRows().get(0).getGroupKey());
    }

    // 功能：PD 主角色只看到自己主 PD 项目的评分行，其它项目数据不泄露（与总裁分支并列的 PD 口径）
    @Test
    void pdShouldOnlySeeOwnPrimaryProjectsInMatrix() {
        seedPeriod("CALIBRATING");
        seedEmployee("ASSESSOR1");
        seedEmployee("EMP_PD_OWNER");
        seedEmployee("EMP_PD_OTHER");
        seedUser("U_PD_OWNER", "pd_cal", "EMP_PD_OWNER");

        seedProject("PRJ_PD_OWN", "P2", "测试项目2.3");
        seedProject("PRJ_OTHER", "P2", "测试项目2.0");
        seedPdAssignment("PRJ_PD_OWN", "P2", "EMP_PD_OWNER");
        seedPdAssignment("PRJ_OTHER", "P2", "EMP_PD_OTHER");

        // 自有项目员工 EMP_A；他人项目员工 EMP_B
        seedResult("EMP_A", "4.0000");
        seedResult("EMP_B", "3.0000");
        seedProjectTask("EMP_A", "PRJ_PD_OWN", "P2");
        seedProjectTask("EMP_B", "PRJ_OTHER", "P2");

        auth("pd_cal", "PD");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        List<String> assessees = matrix.getRows().stream()
                .map(CalibrationMatrixResponse.Row::getAssesseeId).toList();
        assertEquals(1, assessees.size());
        assertTrue(assessees.contains("EMP_A"));
        assertFalse(assessees.contains("EMP_B"));
        assertEquals(1, matrix.getSummary().size());
        assertEquals("project:PRJ_PD_OWN|P2", matrix.getSummary().get(0).getKey());
        assertEquals("测试项目2.3·P2", matrix.getSummary().get(0).getLabel());
    }

    // 功能：ADMIN 不受项目过滤，看到全部结果行
    @Test
    void adminShouldSeeAllProjectsInMatrix() {
        seedPeriod("CALIBRATING");
        seedEmployee("ASSESSOR1");
        seedEmployee("EMP_ADMIN");
        seedUser("U_ADMIN", "admin_cal", "EMP_ADMIN");

        seedProject("PRJ_OWN", "P2", "测试项目2.3");
        seedProject("PRJ_OTHER", "P2", "测试项目2.0");
        seedPdAssignment("PRJ_OWN", "P2", "EMP_ADMIN");

        seedResult("EMP_A", "4.0000");
        seedResult("EMP_B", "3.0000");
        seedProjectTask("EMP_A", "PRJ_OWN", "P2");
        seedProjectTask("EMP_B", "PRJ_OTHER", "P2");

        auth("admin_cal", "ADMIN");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        // ADMIN 不过滤：两个项目员工都在
        assertEquals(2, matrix.getRows().size());
        assertEquals(2, matrix.getSummary().size());
    }

    // 功能：同一员工在多个项目/阶段有任务时逐组展开——分别出现在各自组，不再取 min-id 单一归组
    @Test
    void matrixShouldExpandSameEmployeeAcrossMultipleGroups() {
        seedPeriod("CALIBRATING");
        seedEmployee("ASSESSOR1");
        // 同一员工 EMP_A 同时参与两个项目
        seedProject("PRJ_A", "P2", "测试项目2.0");
        seedProject("PRJ_B", "P2", "测试项目2.3");
        seedResult("EMP_A", "4.0000");
        seedProjectTask("EMP_A", "PRJ_A", "P2");
        seedProjectTask("EMP_A", "PRJ_B", "P2");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        // 两个项目各成一组，员工各出现一行
        assertEquals(2, matrix.getSummary().size());
        assertEquals(2, matrix.getRows().size());
        List<String> keys = matrix.getRows().stream()
                .map(CalibrationMatrixResponse.Row::getGroupKey).sorted().toList();
        assertEquals(List.of("project:PRJ_A|P2", "project:PRJ_B|P2"), keys);
        // 每个组都携带正确的阶段与项目名
        CalibrationMatrixResponse.Row rowA = matrix.getRows().stream()
                .filter(r -> "project:PRJ_A|P2".equals(r.getGroupKey())).findFirst().orElseThrow();
        CalibrationMatrixResponse.Row rowB = matrix.getRows().stream()
                .filter(r -> "project:PRJ_B|P2".equals(r.getGroupKey())).findFirst().orElseThrow();
        assertEquals("测试项目2.0·P2", rowA.getGroupLabel());
        assertEquals("测试项目2.3·P2", rowB.getGroupLabel());
    }

    // 功能：多项目员工各项目行小计按各自任务的 assessment_score 独立重算，不再共享同一 composite（修复 2.65 问题）
    @Test
    void matrixShouldRecomputePerTaskSubtotalForMultiProjectEmployee() {
        seedPeriod("CALIBRATING");
        seedEmployee("ASSESSOR1");
        seedProject("P006", "P2", "项目P006");
        seedProject("P007", "P2", "项目P007");
        Long kpiId = seedProjectKpi("PD", "P2", "1.0000");

        // 周期级 composite 为 2.65（旧口径共享值），但两项目任务小计应各自独立
        seedResult("EMP_MULTI", "2.6500");
        seedProjectTaskWithScore("EMP_MULTI", "P006", "P2", "PD", kpiId, "3.0");
        seedProjectTaskWithScore("EMP_MULTI", "P007", "P2", "PD", kpiId, "4.5");

        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        assertEquals(2, matrix.getRows().size());
        CalibrationMatrixResponse.Row p006 = matrix.getRows().stream()
                .filter(r -> "project:P006|P2".equals(r.getGroupKey())).findFirst().orElseThrow();
        CalibrationMatrixResponse.Row p007 = matrix.getRows().stream()
                .filter(r -> "project:P007|P2".equals(r.getGroupKey())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3.0000").compareTo(p006.getOriginalScore()));
        assertEquals(0, new BigDecimal("4.5000").compareTo(p007.getOriginalScore()));
        assertNotEquals(p006.getOriginalScore(), p007.getOriginalScore());
        // 每行带出各自任务的逐 KPI 明细
        assertNotNull(p006.getKpis());
        assertNotNull(p007.getKpis());
        assertEquals(1, p006.getKpis().size());
    }

    // 功能：单项改分写 calibrated_score（不覆盖原始分）+ 单项审计 old/new + 返回重算小计，周期级 composite 不动
    @Test
    void adjustKpiShouldWriteOverrideAuditAndReturnSubtotal() {
        seedPeriod("CALIBRATING");
        seedProject();
        seedEmployee("ASSESSOR1");
        Long kpiId = seedProjectKpi("PD", "P2", "1.0000");
        seedResult("EMP_A", "4.0000");
        AssessmentTask task = seedProjectTaskWithScore("EMP_A", kpiId, "4.0");

        AdjustKpiResult res = calibrationService.adjustKpi(PERIOD, task.getId(), kpiId,
                new BigDecimal("4.5"), "评估人评分尺度偏差");

        // 返回重算后小计：单指标权重 1.0，校准后小计即 4.5
        assertEquals(0, new BigDecimal("4.0000").compareTo(res.getOriginalSubtotal()));
        assertEquals(0, new BigDecimal("4.5000").compareTo(res.getAdjustedSubtotal()));

        // 覆盖分写 calibrated_score，评估人原始分 score 不被覆盖
        AssessmentScore s = scoreMapper.selectOne(new LambdaQueryWrapper<AssessmentScore>()
                .eq(AssessmentScore::getTaskId, task.getId())
                .eq(AssessmentScore::getKpiConfigId, kpiId));
        assertEquals(0, new BigDecimal("4.0").compareTo(s.getScore()));
        assertEquals(0, new BigDecimal("4.5").compareTo(s.getCalibratedScore()));

        // 审计行记录单项 old/new
        ScoreKpiAdjustment audit = kpiAdjustmentMapper.selectOne(new LambdaQueryWrapper<ScoreKpiAdjustment>()
                .eq(ScoreKpiAdjustment::getTaskId, task.getId())
                .eq(ScoreKpiAdjustment::getKpiConfigId, kpiId));
        assertNotNull(audit);
        assertEquals(0, new BigDecimal("4.0").compareTo(audit.getOldScore()));
        assertEquals(0, new BigDecimal("4.5").compareTo(audit.getNewScore()));
        assertEquals("评估人评分尺度偏差", audit.getReason());

        // 周期级 composite 不动：assessment_result 仍为原始 4.0
        AssessmentResult result = resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, PERIOD)
                .eq(AssessmentResult::getAssesseeId, "EMP_A"));
        assertEquals(0, new BigDecimal("4.0000").compareTo(result.getOriginalScore()));
        assertEquals(0, new BigDecimal("4.0000").compareTo(result.getAdjustedScore()));

        // 项目小计落库
        AssessmentProjectSubtotal st = projectSubtotalMapper.selectOne(new LambdaQueryWrapper<AssessmentProjectSubtotal>()
                .eq(AssessmentProjectSubtotal::getPeriodId, PERIOD)
                .eq(AssessmentProjectSubtotal::getAssesseeId, "EMP_A")
                .eq(AssessmentProjectSubtotal::getProjectCode, PROJECT)
                .eq(AssessmentProjectSubtotal::getProjectStage, "P2"));
        assertNotNull(st);
        assertEquals(0, new BigDecimal("4.5000").compareTo(st.getAdjustedSubtotal()));
    }

    // ================= 辅助：种子数据 =================

    // 功能：PD-A 提交后 PD-B 的提交按钮仍可用——项目级提交粒度，各自项目独立（问题3）
    @Test
    void pdSubmitShouldNotDisableOtherPdsButton() {
        seedPeriod("CALIBRATING");
        seedEmployee("ASSESSOR1");
        seedEmployee("EMP_PD_OWNER");
        seedEmployee("EMP_PD_OTHER");
        seedUser("U_PD_OWNER2", "pd_a", "EMP_PD_OWNER");
        seedUser("U_PD_OTHER2", "pd_b", "EMP_PD_OTHER");

        seedProject("PRJ_PD_A", "P2", "项目A");
        seedProject("PRJ_PD_B", "P2", "项目B");
        seedPdAssignment("PRJ_PD_A", "P2", "EMP_PD_OWNER");
        seedPdAssignment("PRJ_PD_B", "P2", "EMP_PD_OTHER");

        seedResult("EMP_A", "4.0000");
        seedResult("EMP_B", "3.0000");
        seedProjectTask("EMP_A", "PRJ_PD_A", "P2");
        seedProjectTask("EMP_B", "PRJ_PD_B", "P2");

        // PD-A（EMP_PD_OWNER）已提交 PRJ_PD_A，PRJ_PD_B 尚未提交
        seedSubmission(PERIOD, "PRJ_PD_A", "P2", "EMP_PD_OWNER");

        auth("pd_b", "PD");
        CalibrationMatrixResponse matrix = calibrationService.getCalibrationMatrix(PERIOD);

        assertFalse(matrix.isSubmitted(), "PD-B 名下项目未提交，提交按钮应仍可用");
        assertNull(matrix.getCalibrationSubmittedAt(), "仅 PD-A 提交，周期级时间戳应仍为空");
    }

    // 辅助：插入项目级校准提交记录（submitted_at 非空）
    private void seedSubmission(String periodId, String code, String stage, String employeeId) {
        CalibrationSubmission s = new CalibrationSubmission();
        s.setPeriodId(periodId);
        s.setProjectCode(code);
        s.setProjectStage(stage);
        s.setSubmittedByEmployeeId(employeeId);
        s.setSubmittedAt(LocalDateTime.now());
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        calibrationSubmissionMapper.insert(s);
    }

    private void seedPeriod(String status) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(PERIOD);
        period.setPeriodName("校准测试周期");
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 6, 30));
        period.setStatus(status);
        periodMapper.insert(period);
    }

    private void seedProject() {
        Project project = new Project();
        project.setProjectCode(PROJECT);
        project.setProjectName("项目一");
        project.setProjectStage("P2");
        project.setStatus("ACTIVE");
        project.setStageConfirmed(false);
        projectMapper.insert(project);
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

    private void seedResult(String assesseeId, String originalScore) {
        seedEmployee(assesseeId);

        AssessmentResult result = new AssessmentResult();
        result.setPeriodId(PERIOD);
        result.setAssesseeId(assesseeId);
        result.setOriginalScore(new BigDecimal(originalScore));
        result.setAdjustedScore(new BigDecimal(originalScore));
        result.setDeleted(0);
        result.setVersion(0L);
        resultMapper.insert(result);
    }

    private void seedConfirmation(String assesseeId, String status, String returnReason) {
        ProjectConfirmation c = new ProjectConfirmation();
        c.setPeriodId(PERIOD);
        c.setProjectCode(PROJECT);
        c.setAssesseeId(assesseeId);
        c.setStatus(status);
        c.setReturnCount(0);
        c.setReturnReason(returnReason);
        projectConfirmationMapper.insert(c);
    }

    private AssessmentTask seedProjectTask(String assesseeId) {
        return seedTask(assesseeId, "PROJECT");
    }

    private AssessmentTask seedFunctionalTask(String assesseeId) {
        return seedTask(assesseeId, "FUNCTIONAL");
    }

    private void seedPendingTask(String assesseeId) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(PERIOD);
        task.setAssessorId("ASSESSOR1");
        task.setAssesseeId(assesseeId);
        task.setProjectCode(PROJECT);
        task.setProjectStage("P2");
        task.setTaskType("PROJECT");
        task.setStatus("IN_PROGRESS");
        taskMapper.insert(task);
    }

    private AssessmentTask seedTask(String assesseeId, String taskType) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(PERIOD);
        task.setAssessorId("ASSESSOR1");
        task.setAssesseeId(assesseeId);
        task.setProjectCode("PROJECT".equals(taskType) ? PROJECT : null);
        task.setProjectStage("PROJECT".equals(taskType) ? "P2" : null);
        task.setTaskType(taskType);
        task.setStatus("SUBMITTED");
        taskMapper.insert(task);
        return task;
    }

    // 辅助：插入项目 KPI 配置（先补种角色满足 FK），返回 kpiConfigId；单指标权重 1.0 时得分即小计
    private Long seedProjectKpi(String roleCode, String stage, String weight) {
        seedRoleIfAbsent(roleCode);
        ProjectKpiConfig kpi = new ProjectKpiConfig();
        kpi.setProjectRoleCode(roleCode);
        kpi.setProjectStage(stage);
        kpi.setKpiName("测试指标-" + roleCode);
        kpi.setWeight(new BigDecimal(weight));
        kpi.setSortOrder(1);
        kpi.setIsActive(true);
        kpi.setDeleted(0);
        kpi.setVersion(0L);
        projectKpiMapper.insert(kpi);
        return kpi.getId();
    }

    // 辅助：为被考核人插入项目角色分配（resolveIndicators 反查 roleCode 用）
    private void seedAssesseeRole(String assesseeId, String projectCode, String stage, String roleCode) {
        seedRoleIfAbsent(roleCode);
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode(roleCode);
        a.setEmployeeId(assesseeId);
        a.setIsPrimary(false);
        a.setDeleted(0);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：为任务插入一条 PROJECT 评分
    private void seedScore(Long taskId, Long kpiConfigId, String score) {
        AssessmentScore s = new AssessmentScore();
        s.setTaskId(taskId);
        s.setKpiConfigId(kpiConfigId);
        s.setKpiType("PROJECT");
        s.setScore(new BigDecimal(score));
        s.setStatus("SUBMITTED");
        s.setDeleted(0);
        s.setVersion(0L);
        scoreMapper.insert(s);
    }

    // 辅助：默认 PRJ1/P2 + PD 角色补齐「角色 + 任务 + 评分」，返回任务
    private AssessmentTask seedProjectTaskWithScore(String assesseeId, Long kpiId, String score) {
        return seedProjectTaskWithScore(assesseeId, PROJECT, "P2", "PD", kpiId, score);
    }

    // 辅助：指定项目/阶段/角色补齐「角色 + 任务 + 评分」，返回任务
    private AssessmentTask seedProjectTaskWithScore(String assesseeId, String code, String stage,
                                                    String roleCode, Long kpiId, String score) {
        seedAssesseeRole(assesseeId, code, stage, roleCode);
        AssessmentTask task = seedProjectTask(assesseeId, code, stage);
        seedScore(task.getId(), kpiId, score);
        return task;
    }

    // 辅助：插入指定编码/阶段的项目
    private void seedProject(String code, String stage) {
        Project project = new Project();
        project.setProjectCode(code);
        project.setProjectName("项目" + code);
        project.setProjectStage(stage);
        project.setStatus("ACTIVE");
        project.setStageConfirmed(false);
        projectMapper.insert(project);
    }

    // 辅助：插入指定编码/阶段/名称的项目
    private void seedProject(String code, String stage, String name) {
        Project project = new Project();
        project.setProjectCode(code);
        project.setProjectName(name);
        project.setProjectStage(stage);
        project.setStatus("ACTIVE");
        project.setStageConfirmed(false);
        projectMapper.insert(project);
    }

    // 辅助：插入指定项目编码/阶段的 SUBMITTED 项目任务
    private AssessmentTask seedProjectTask(String assesseeId, String projectCode, String projectStage) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(PERIOD);
        task.setAssessorId("ASSESSOR1");
        task.setAssesseeId(assesseeId);
        task.setProjectCode(projectCode);
        task.setProjectStage(projectStage);
        task.setTaskType("PROJECT");
        task.setStatus("SUBMITTED");
        taskMapper.insert(task);
        return task;
    }

    // 辅助：插入主总裁角色分配（PRESIDENT 角色已在 V27 种子，无需重复插 project_role）
    private void seedPresidentAssignment(String projectCode, String stage, String employeeId) {
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode("PRESIDENT");
        a.setEmployeeId(employeeId);
        a.setIsPrimary(true);
        a.setDeleted(0);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：插入主 PD 角色分配（project_role_assignment 外键 fk_pra_role 要求 PD 角色存在，
    //   测试库仅 V27 种了 PRESIDENT，故先补种 PD 角色）
    private void seedPdAssignment(String projectCode, String stage, String employeeId) {
        seedRoleIfAbsent("PD");
        ProjectRoleAssignment a = new ProjectRoleAssignment();
        a.setProjectCode(projectCode);
        a.setProjectStage(stage);
        a.setProjectRoleCode("PD");
        a.setEmployeeId(employeeId);
        a.setIsPrimary(true);
        a.setDeleted(0);
        roleAssignmentMapper.insert(a);
    }

    // 辅助：按需插入项目角色（role_code 业务主键，已存在则跳过，避免同事务内重复 insert 撞主键）
    private void seedRoleIfAbsent(String roleCode) {
        if (projectRoleMapper.selectById(roleCode) == null) {
            ProjectRole role = new ProjectRole();
            role.setRoleCode(roleCode);
            role.setRoleName("角色" + roleCode);
            projectRoleMapper.insert(role);
        }
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

    // 辅助：设置当前登录用户及其角色
    private void auth(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
