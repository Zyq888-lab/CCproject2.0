// 模块用途：ResultService 单元测试——覆盖 composite 聚合落库、单组件权重重归一化、改分保留原始分
// 依赖文件：ResultService.java, AssessmentResultMapper.java, ScoreCalculator.java, 各 Mapper
// 修改注意：@SpringBootTest + @Transactional（测试库 PostgreSQL，每个用例独立回滚）
package com.jifeng.assessment.result;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.calibration.AssessmentResult;
import com.jifeng.assessment.calibration.AssessmentResultMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.participation.EmployeeProjectParticipation;
import com.jifeng.assessment.participation.ParticipationMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import com.jifeng.assessment.score.AssessmentScore;
import com.jifeng.assessment.score.ScoreMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResultServiceTest {

    @Autowired private ResultService resultService;
    @Autowired private PeriodService periodService;
    @Autowired private AssessmentResultMapper resultMapper;
    @Autowired private PeriodMapper periodMapper;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private PositionConfigMapper positionConfigMapper;
    @Autowired private ProjectRoleMapper projectRoleMapper;
    @Autowired private ProjectMapper projectMapper;
    @Autowired private ProjectKpiMapper projectKpiMapper;
    @Autowired private FuncKpiMapper funcKpiMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private ParticipationMapper participationMapper;

    private static final String CATEGORY = "研发技术类";
    private static final String POSITION = "整椅研发岗";

    // 功能：双组件员工（项目+职能）composite 正确落库——4.0×0.7 + 3.0×0.3 = 3.7000
    @Test
    void generateShouldComputeCompositeForDualComponentEmployee() {
        seedPeriod();
        seedEmployee("EMP_DUAL");
        seedEmployee("ASSESSOR1");
        seedEmployee("ASSESSOR2");
        seedPositionConfig(new BigDecimal("0.7000"), new BigDecimal("0.3000"));
        Long projKpiId = seedProjectKpi(new BigDecimal("1.0000"));
        Long funcKpiId = seedFuncKpi(new BigDecimal("1.0000"));

        Long projTaskId = seedTask("EMP_DUAL", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        Long funcTaskId = seedTask("EMP_DUAL", "ASSESSOR2", null, null, "FUNCTIONAL", "SUBMITTED");
        seedScore(projTaskId, projKpiId, "PROJECT", new BigDecimal("4.0"));
        seedScore(funcTaskId, funcKpiId, "FUNCTIONAL", new BigDecimal("3.0"));
        seedParticipation("EMP_DUAL", "PRJ1", "P2", new BigDecimal("100"));

        int generated = resultService.generateResults("PERIOD-001");

        assertEquals(1, generated);
        AssessmentResult result = resultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                        .eq(AssessmentResult::getAssesseeId, "EMP_DUAL"));
        assertNotNull(result);
        assertEquals(0, new BigDecimal("3.7000").compareTo(result.getOriginalScore()));
        assertEquals(0, new BigDecimal("3.7000").compareTo(result.getAdjustedScore()));
    }

    // 功能：单组件员工（仅项目）权重重归一化——composite 不再乘 0.7，直接等于项目加权分 4.0000
    @Test
    void generateShouldRenormalizeSingleComponentEmployee() {
        seedPeriod();
        seedEmployee("EMP_ONLY_PROJ");
        seedEmployee("ASSESSOR1");
        seedPositionConfig(new BigDecimal("0.7000"), new BigDecimal("0.3000"));
        Long projKpiId = seedProjectKpi(new BigDecimal("1.0000"));

        Long projTaskId = seedTask("EMP_ONLY_PROJ", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        seedScore(projTaskId, projKpiId, "PROJECT", new BigDecimal("4.0"));
        seedParticipation("EMP_ONLY_PROJ", "PRJ1", "P2", new BigDecimal("100"));

        resultService.generateResults("PERIOD-001");

        AssessmentResult result = resultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                        .eq(AssessmentResult::getAssesseeId, "EMP_ONLY_PROJ"));
        assertNotNull(result);
        assertEquals(0, new BigDecimal("4.0000").compareTo(result.getOriginalScore()));
    }

    // 功能：已手动改分的行重生成时保留 adjusted_score，仅刷新 original_score
    @Test
    void regenerateShouldPreserveManualAdjustment() {
        seedPeriod();
        seedEmployee("EMP_ADJ");
        seedEmployee("ASSESSOR1");
        seedEmployee("ASSESSOR2");
        seedPositionConfig(new BigDecimal("0.7000"), new BigDecimal("0.3000"));
        Long projKpiId = seedProjectKpi(new BigDecimal("1.0000"));
        Long funcKpiId = seedFuncKpi(new BigDecimal("1.0000"));

        Long projTaskId = seedTask("EMP_ADJ", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        Long funcTaskId = seedTask("EMP_ADJ", "ASSESSOR2", null, null, "FUNCTIONAL", "SUBMITTED");
        seedScore(projTaskId, projKpiId, "PROJECT", new BigDecimal("4.0"));
        seedScore(funcTaskId, funcKpiId, "FUNCTIONAL", new BigDecimal("3.0"));
        seedParticipation("EMP_ADJ", "PRJ1", "P2", new BigDecimal("100"));

        resultService.generateResults("PERIOD-001");

        // 模拟 PD 改分：adjusted_score 手动改为 4.5
        AssessmentResult result = resultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                        .eq(AssessmentResult::getAssesseeId, "EMP_ADJ"));
        result.setAdjustedScore(new BigDecimal("4.5000"));
        resultMapper.updateById(result);

        // 重生成：原始分刷新为 3.7000，调整分保留 4.5000
        resultService.generateResults("PERIOD-001");

        AssessmentResult reloaded = resultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                        .eq(AssessmentResult::getAssesseeId, "EMP_ADJ"));
        assertEquals(0, new BigDecimal("3.7000").compareTo(reloaded.getOriginalScore()));
        assertEquals(0, new BigDecimal("4.5000").compareTo(reloaded.getAdjustedScore()));
    }

    // 功能：完整性软门——未 SUBMITTED 员工跳空不生成结果行，仅 SUBMITTED 员工落库
    @Test
    void generateShouldSkipUnsubmittedAssessees() {
        seedPeriod();
        seedEmployee("EMP_SUBMITTED");
        seedEmployee("EMP_PENDING");
        seedEmployee("ASSESSOR1");
        seedPositionConfig(new BigDecimal("0.7000"), new BigDecimal("0.3000"));
        Long projKpiId = seedProjectKpi(new BigDecimal("1.0000"));

        Long submittedTaskId = seedTask("EMP_SUBMITTED", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        seedScore(submittedTaskId, projKpiId, "PROJECT", new BigDecimal("4.0"));
        seedParticipation("EMP_SUBMITTED", "PRJ1", "P2", new BigDecimal("100"));
        seedTask("EMP_PENDING", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "IN_PROGRESS");

        int generated = resultService.generateResults("PERIOD-001");

        assertEquals(1, generated);
        assertNotNull(resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                .eq(AssessmentResult::getAssesseeId, "EMP_SUBMITTED")));
        assertNull(resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                .eq(AssessmentResult::getAssesseeId, "EMP_PENDING")));
    }

    // 功能：半提交员工（部分任务已提交、部分仍在打分）不生成结果行——严格完整性语义，
    //   全部非 CANCELED 任务均 SUBMITTED 才落库，避免半成 composite 发布；半提交员工归入未提交桶
    @Test
    void generateShouldSkipPartiallySubmittedAssessee() {
        seedPeriod();
        seedEmployee("EMP_FULL");
        seedEmployee("EMP_HALF");
        seedEmployee("ASSESSOR1");
        seedPositionConfig(new BigDecimal("0.7000"), new BigDecimal("0.3000"));
        Long projKpiId = seedProjectKpi(new BigDecimal("1.0000"));

        // EMP_FULL：唯一 PROJECT 任务已提交 → 生成结果行
        Long fullTaskId = seedTask("EMP_FULL", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        seedScore(fullTaskId, projKpiId, "PROJECT", new BigDecimal("4.0"));
        seedParticipation("EMP_FULL", "PRJ1", "P2", new BigDecimal("100"));

        // EMP_HALF：PROJECT 已提交但 FUNCTIONAL 仍在打分中 → 不生成结果行
        Long halfSubmittedTaskId = seedTask("EMP_HALF", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        seedScore(halfSubmittedTaskId, projKpiId, "PROJECT", new BigDecimal("4.0"));
        seedParticipation("EMP_HALF", "PRJ1", "P2", new BigDecimal("100"));
        seedTask("EMP_HALF", "ASSESSOR1", null, null, "FUNCTIONAL", "IN_PROGRESS");

        int generated = resultService.generateResults("PERIOD-001");

        assertEquals(1, generated);
        assertNotNull(resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                .eq(AssessmentResult::getAssesseeId, "EMP_FULL")));
        assertNull(resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                .eq(AssessmentResult::getAssesseeId, "EMP_HALF")));
    }

    // 功能：统计未提交员工数——PENDING/IN_PROGRESS 各 1 人计入，SUBMITTED/CANCELED 不计入
    @Test
    void countUnsubmittedShouldCountActiveButNotSubmittedAssessees() {
        seedPeriod();
        seedEmployee("EMP_PENDING");
        seedEmployee("EMP_INPROGRESS");
        seedEmployee("EMP_SUBMITTED");
        seedEmployee("EMP_CANCELED");
        seedEmployee("ASSESSOR1");

        seedTask("EMP_PENDING", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "PENDING");
        seedTask("EMP_INPROGRESS", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "IN_PROGRESS");
        seedTask("EMP_SUBMITTED", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        seedTask("EMP_CANCELED", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "CANCELED");

        assertEquals(2, resultService.countUnsubmitted("PERIOD-001"));
    }

    // 功能：进入校准触发结果生成——SUBMITTED 员工落库、未提交员工跳空、未提交计数=1
    @Test
    void enterCalibrationShouldGenerateResultsAndSkipUnsubmitted() {
        seedPeriod();
        seedEmployee("EMP_SUBMITTED");
        seedEmployee("EMP_PENDING");
        seedEmployee("ASSESSOR1");
        seedPositionConfig(new BigDecimal("0.7000"), new BigDecimal("0.3000"));
        Long projKpiId = seedProjectKpi(new BigDecimal("1.0000"));

        Long submittedTaskId = seedTask("EMP_SUBMITTED", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        seedScore(submittedTaskId, projKpiId, "PROJECT", new BigDecimal("4.0"));
        seedParticipation("EMP_SUBMITTED", "PRJ1", "P2", new BigDecimal("100"));
        seedTask("EMP_PENDING", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "IN_PROGRESS");

        AssessmentPeriod calibrated = periodService.enterCalibration("PERIOD-001");

        assertEquals("CALIBRATING", calibrated.getStatus());
        assertNotNull(resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                .eq(AssessmentResult::getAssesseeId, "EMP_SUBMITTED")));
        assertNull(resultMapper.selectOne(new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                .eq(AssessmentResult::getAssesseeId, "EMP_PENDING")));
        assertEquals(1, resultService.countUnsubmitted("PERIOD-001"));
    }

    // 功能：单任务两个项目KPI权重各 1.0 → 归一化后各 0.5，单任务得分不越界（5.0×0.5 + 4.0×0.5 = 4.5 ≤ 5）
    @Test
    void generateShouldNormalizeKpiWeightsWhenSumExceedsOne() {
        seedPeriod();
        seedEmployee("EMP_2KPI");
        seedEmployee("ASSESSOR1");
        seedPositionConfig(new BigDecimal("0.7000"), new BigDecimal("0.3000"));
        List<Long> kpiIds = seedTwoProjectKpis(new BigDecimal("1.0000"), new BigDecimal("1.0000"));

        Long taskId = seedTask("EMP_2KPI", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        seedScore(taskId, kpiIds.get(0), "PROJECT", new BigDecimal("5.0"));
        seedScore(taskId, kpiIds.get(1), "PROJECT", new BigDecimal("4.0"));
        seedParticipation("EMP_2KPI", "PRJ1", "P2", new BigDecimal("100"));

        resultService.generateResults("PERIOD-001");

        AssessmentResult result = resultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                        .eq(AssessmentResult::getAssesseeId, "EMP_2KPI"));
        assertNotNull(result);
        assertEquals(0, new BigDecimal("4.5000").compareTo(result.getOriginalScore()));
        assertTrue(result.getOriginalScore().compareTo(new BigDecimal("5.0")) <= 0);
    }

    // 功能：单KPI权重 0.7 → 归一化为 1.0，得分不被低估（4.0×1.0 = 4.0，而非 4.0×0.7 = 2.8）
    @Test
    void generateShouldNormalizeSingleKpiWeightBelowOne() {
        seedPeriod();
        seedEmployee("EMP_SINGLE");
        seedEmployee("ASSESSOR1");
        seedPositionConfig(new BigDecimal("0.7000"), new BigDecimal("0.3000"));
        Long kpiId = seedProjectKpi(new BigDecimal("0.7000"));

        Long taskId = seedTask("EMP_SINGLE", "ASSESSOR1", "PRJ1", "P2", "PROJECT", "SUBMITTED");
        seedScore(taskId, kpiId, "PROJECT", new BigDecimal("4.0"));
        seedParticipation("EMP_SINGLE", "PRJ1", "P2", new BigDecimal("100"));

        resultService.generateResults("PERIOD-001");

        AssessmentResult result = resultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getPeriodId, "PERIOD-001")
                        .eq(AssessmentResult::getAssesseeId, "EMP_SINGLE"));
        assertNotNull(result);
        assertEquals(0, new BigDecimal("4.0000").compareTo(result.getOriginalScore()));
    }

    // ================= 辅助：种子数据 =================

    private void seedPeriod() {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId("PERIOD-001");
        period.setPeriodName("测试周期");
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 6, 30));
        period.setStatus("ONGOING");
        periodMapper.insert(period);
    }

    private void seedEmployee(String employeeId) {
        Employee e = new Employee();
        e.setEmployeeId(employeeId);
        e.setName("员工" + employeeId);
        e.setEmail(employeeId + "@test.com");
        e.setCategory(CATEGORY);
        e.setPosition(POSITION);
        e.setOrgName("信息部");
        e.setStatus("ACTIVE");
        employeeMapper.insert(e);
    }

    private void seedPositionConfig(BigDecimal projectWeight, BigDecimal funcWeight) {
        PositionAssessmentConfig c = new PositionAssessmentConfig();
        c.setCategory(CATEGORY);
        c.setPosition(POSITION);
        c.setProjectWeight(projectWeight);
        c.setFuncWeight(funcWeight);
        positionConfigMapper.insert(c);
    }

    private Long seedProjectKpi(BigDecimal weight) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("PDL");
        role.setRoleName("项目负责人");
        projectRoleMapper.insert(role);

        Project project = new Project();
        project.setProjectCode("PRJ1");
        project.setProjectName("项目一");
        project.setProjectStage("P2");
        project.setStatus("ACTIVE");
        projectMapper.insert(project);

        ProjectKpiConfig kpi = new ProjectKpiConfig();
        kpi.setProjectRoleCode("PDL");
        kpi.setProjectStage("P2");
        kpi.setKpiName("项目KPI");
        kpi.setWeight(weight);
        kpi.setSortOrder(1);
        kpi.setIsActive(true);
        projectKpiMapper.insert(kpi);
        return kpi.getId();
    }

    // 辅助方法：同角色同阶段下创建两个项目KPI（用于验证 KPI 权重重归一化——两权重各 1.0 求和 2.0）
    private List<Long> seedTwoProjectKpis(BigDecimal weight1, BigDecimal weight2) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode("PDL");
        role.setRoleName("项目负责人");
        projectRoleMapper.insert(role);

        Project project = new Project();
        project.setProjectCode("PRJ1");
        project.setProjectName("项目一");
        project.setProjectStage("P2");
        project.setStatus("ACTIVE");
        projectMapper.insert(project);

        ProjectKpiConfig kpi1 = new ProjectKpiConfig();
        kpi1.setProjectRoleCode("PDL");
        kpi1.setProjectStage("P2");
        kpi1.setKpiName("项目KPI-A");
        kpi1.setWeight(weight1);
        kpi1.setSortOrder(1);
        kpi1.setIsActive(true);
        projectKpiMapper.insert(kpi1);

        ProjectKpiConfig kpi2 = new ProjectKpiConfig();
        kpi2.setProjectRoleCode("PDL");
        kpi2.setProjectStage("P2");
        kpi2.setKpiName("项目KPI-B");
        kpi2.setWeight(weight2);
        kpi2.setSortOrder(2);
        kpi2.setIsActive(true);
        projectKpiMapper.insert(kpi2);

        return List.of(kpi1.getId(), kpi2.getId());
    }

    private Long seedFuncKpi(BigDecimal weight) {
        FuncKpiConfig kpi = new FuncKpiConfig();
        kpi.setCategory(CATEGORY);
        kpi.setPosition(POSITION);
        kpi.setKpiName("职能KPI");
        kpi.setWeight(weight);
        kpi.setSortOrder(1);
        kpi.setIsActive(true);
        funcKpiMapper.insert(kpi);
        return kpi.getId();
    }

    private Long seedTask(String assesseeId, String assessorId, String projectCode,
                          String projectStage, String taskType, String status) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId("PERIOD-001");
        task.setAssessorId(assessorId);
        task.setAssesseeId(assesseeId);
        task.setProjectCode(projectCode);
        task.setProjectStage(projectStage);
        task.setTaskType(taskType);
        task.setStatus(status);
        taskMapper.insert(task);
        return task.getId();
    }

    private void seedScore(Long taskId, Long kpiConfigId, String kpiType, BigDecimal score) {
        AssessmentScore s = new AssessmentScore();
        s.setTaskId(taskId);
        s.setKpiConfigId(kpiConfigId);
        s.setKpiType(kpiType);
        s.setScore(score);
        s.setStatus("SUBMITTED");
        scoreMapper.insert(s);
    }

    private void seedParticipation(String employeeId, String projectCode, String projectStage,
                                   BigDecimal rate) {
        EmployeeProjectParticipation p = new EmployeeProjectParticipation();
        p.setEmployeeId(employeeId);
        p.setProjectCode(projectCode);
        p.setProjectStage(projectStage);
        p.setParticipationRate(rate);
        p.setStatus("APPROVED");
        p.setPeriodId("PERIOD-001");
        participationMapper.insert(p);
    }
}
