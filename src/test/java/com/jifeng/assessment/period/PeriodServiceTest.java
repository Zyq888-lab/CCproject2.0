// 模块用途：PeriodService 单元测试——覆盖CRUD、活跃周期唯一约束、状态机（INIT→ONGOING→CALIBRATING→CONFIRMED→PUBLISHED→COMPLETED + abort）
// 依赖文件：PeriodService.java, AssessmentPeriod.java, PeriodMapper.java
// 修改注意：@SpringBootTest + @Transactional（测试库 PostgreSQL，每个用例独立回滚）
package com.jifeng.assessment.period;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.DiscrepancyLog;
import com.jifeng.assessment.task.DiscrepancyLogMapper;
import com.jifeng.assessment.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PeriodServiceTest {

    @Autowired
    private PeriodService periodService;
    @Autowired
    private PeriodMapper periodMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired
    private ProjectConfirmationMapper projectConfirmationMapper;
    @Autowired
    private DiscrepancyLogMapper discrepancyLogMapper;

    // 辅助方法：创建测试周期
    private AssessmentPeriod createTestPeriod(String name) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodName(name);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 6, 30));
        return periodService.createPeriod(period);
    }

    // 辅助方法：INIT→ONGOING 原子翻转——发起考核的真实入口是 TaskGeneratorService.launch，
    //   此处用底层 updateStatus 直接置态，避免依赖任务生成（startPeriod 端点已删除）
    private void startPeriod(String periodId) {
        periodMapper.updateStatus(periodId, "INIT", "ONGOING");
    }

    // 辅助方法：走完整状态链到 CONFIRMED（INIT→ONGOING→CALIBRATING→CONFIRMED）
    private void walkToConfirmed(String periodId) {
        startPeriod(periodId);
        periodService.enterCalibration(periodId);
        periodService.tryConfirmPeriod(periodId);
    }

    // 辅助方法：走完整状态链到 PUBLISHED（CONFIRMED 后再发布）
    private void walkToPublished(String periodId) {
        walkToConfirmed(periodId);
        periodService.publishPeriod(periodId);
    }

    // 功能：创建考核周期——自动生成periodId，返回完整实体
    @Test
    void shouldCreatePeriod() {
        AssessmentPeriod period = createTestPeriod("2026年上半年考核");
        assertNotNull(period.getPeriodId());
        assertFalse(period.getPeriodId().isEmpty());
        assertEquals("2026年上半年考核", period.getPeriodName());
        assertEquals("INIT", period.getStatus());
        assertEquals(LocalDate.of(2026, 1, 1), period.getStartDate());
        assertEquals(LocalDate.of(2026, 6, 30), period.getEndDate());
    }

    // 功能：活跃周期唯一约束——存在未关闭周期时拒绝创建
    @Test
    void shouldRejectCreateWhenActivePeriodExists() {
        createTestPeriod("2026年上半年考核");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createTestPeriod("2026年下半年考核"));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("未关闭"));
    }

    // 功能：查询考核周期列表，包含所有已创建的周期
    @Test
    void shouldListPeriods() {
        AssessmentPeriod p1 = createTestPeriod("周期A");
        // 必须先关闭p1才能创建第二个
        periodService.abortPeriod(p1.getPeriodId());
        AssessmentPeriod p2 = createTestPeriod("周期B");

        List<AssessmentPeriod> list = periodService.listPeriods(null);
        assertTrue(list.size() >= 2);
        List<String> ids = list.stream().map(AssessmentPeriod::getPeriodId).toList();
        assertTrue(ids.contains(p1.getPeriodId()));
        assertTrue(ids.contains(p2.getPeriodId()));
    }

    // 功能：按status筛选考核周期
    @Test
    void shouldListPeriodsWithStatusFilter() {
        AssessmentPeriod p1 = createTestPeriod("周期A");
        periodService.abortPeriod(p1.getPeriodId());
        createTestPeriod("周期B");  // status=INIT

        List<AssessmentPeriod> completed = periodService.listPeriods("COMPLETED");
        assertEquals(1, completed.size());
        assertEquals("COMPLETED", completed.get(0).getStatus());

        List<AssessmentPeriod> init = periodService.listPeriods("INIT");
        assertEquals(1, init.size());
        assertEquals("INIT", init.get(0).getStatus());
    }

    // 功能：完整状态链后关闭——INIT→ONGOING→CALIBRATING→CONFIRMED→PUBLISHED→COMPLETED
    @Test
    void shouldClosePeriod() {
        AssessmentPeriod period = createTestPeriod("2026年上半年考核");
        assertEquals("INIT", period.getStatus());

        walkToPublished(period.getPeriodId());

        AssessmentPeriod closed = periodService.closePeriod(period.getPeriodId());
        assertEquals("COMPLETED", closed.getStatus());
    }

    // 功能：关闭不存在的周期返回404
    @Test
    void shouldRejectCloseNonexistentPeriod() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.closePeriod("NONEXISTENT-ID"));
        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // 功能：重复关闭已COMPLETED的周期返回400
    @Test
    void shouldRejectCloseAlreadyCompleted() {
        AssessmentPeriod period = createTestPeriod("2026年上半年考核");
        walkToPublished(period.getPeriodId());
        periodService.closePeriod(period.getPeriodId());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.closePeriod(period.getPeriodId()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已关闭"));
    }

    // 功能：关闭活跃周期后可以再次创建新周期
    @Test
    void shouldCreateAfterClosingActive() {
        AssessmentPeriod first = createTestPeriod("第一期");
        periodService.abortPeriod(first.getPeriodId());

        AssessmentPeriod second = createTestPeriod("第二期");
        assertNotNull(second.getPeriodId());
        assertEquals("INIT", second.getStatus());
    }

    // 功能：开始日期晚于结束日期时拒绝创建
    @Test
    void shouldRejectStartDateAfterEndDate() {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodName("日期错误周期");
        period.setStartDate(LocalDate.of(2026, 12, 31));
        period.setEndDate(LocalDate.of(2026, 1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.createPeriod(period));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("开始日期不能晚于结束日期"));
    }

    // 功能：未发布（仅CONFIRMED）时关闭被拒绝——防旁路（需先发布再关闭）
    @Test
    void shouldRejectCloseBeforePublish() {
        AssessmentPeriod period = createTestPeriod("未发布关闭");
        walkToConfirmed(period.getPeriodId()); // CONFIRMED

        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.closePeriod(period.getPeriodId()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("仅已发布"));
    }

    // 功能：进入校准——ONGOING→CALIBRATING
    @Test
    void shouldEnterCalibration() {
        AssessmentPeriod period = createTestPeriod("进入校准");
        startPeriod(period.getPeriodId());

        AssessmentPeriod calibrating = periodService.enterCalibration(period.getPeriodId());
        assertEquals("CALIBRATING", calibrating.getStatus());
    }

    // 功能：非ONGOING（INIT）时进入校准被拒绝
    @Test
    void shouldRejectEnterCalibrationFromInit() {
        AssessmentPeriod period = createTestPeriod("非进行中校准");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.enterCalibration(period.getPeriodId()));
        assertEquals(400, ex.getCode());
    }

    // 功能：周期级确认——CALIBRATING→CONFIRMED 原子翻转（tryConfirmPeriod 返回 true）
    @Test
    void shouldConfirmPeriodAtomically() {
        AssessmentPeriod period = createTestPeriod("确认周期");
        startPeriod(period.getPeriodId());
        periodService.enterCalibration(period.getPeriodId());

        boolean confirmed = periodService.tryConfirmPeriod(period.getPeriodId());

        assertTrue(confirmed);
        assertEquals("CONFIRMED", periodMapper.selectById(period.getPeriodId()).getStatus());
    }

    // 功能：非CALIBRATING（ONGOING）时确认不翻转——返回 false 且状态不变
    @Test
    void shouldNotConfirmWhenNotCalibrating() {
        AssessmentPeriod period = createTestPeriod("非校准确认");
        startPeriod(period.getPeriodId());

        boolean confirmed = periodService.tryConfirmPeriod(period.getPeriodId());

        assertFalse(confirmed);
        assertEquals("ONGOING", periodMapper.selectById(period.getPeriodId()).getStatus());
    }

    // 功能：发布结果——CONFIRMED→PUBLISHED 原子翻转
    @Test
    void shouldPublishPeriod() {
        AssessmentPeriod period = createTestPeriod("发布周期");
        walkToConfirmed(period.getPeriodId());

        AssessmentPeriod published = periodService.publishPeriod(period.getPeriodId());

        assertEquals("PUBLISHED", published.getStatus());
    }

    // 功能：非CONFIRMED（ONGOING）时发布被拒绝
    @Test
    void shouldRejectPublishWhenNotConfirmed() {
        AssessmentPeriod period = createTestPeriod("非确认发布");
        startPeriod(period.getPeriodId()); // ONGOING

        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.publishPeriod(period.getPeriodId()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("仅已确认"));
    }

    // 功能：进入校准时生成项目确认行 + 未分配总裁项目写 NO_PRESIDENT 差异
    @Test
    void shouldGenerateConfirmationAndNoPresidentDiscrepancyOnEnterCalibration() {
        AssessmentPeriod period = createTestPeriod("无总裁差异");
        startPeriod(period.getPeriodId());

        Employee emp = seedEmployee("EMP_NOPRES");
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(period.getPeriodId());
        task.setAssessorId("EMP_NOPRES");
        task.setAssesseeId("EMP_NOPRES");
        task.setProjectCode("PRJ_NOPRES");
        task.setProjectStage("P2");
        task.setTaskType("PROJECT");
        task.setStatus("SUBMITTED");
        taskMapper.insert(task);

        periodService.enterCalibration(period.getPeriodId());

        Long confirmationCount = projectConfirmationMapper.selectCount(
                new LambdaQueryWrapper<ProjectConfirmation>()
                        .eq(ProjectConfirmation::getPeriodId, period.getPeriodId())
                        .eq(ProjectConfirmation::getProjectCode, "PRJ_NOPRES"));
        assertEquals(1, confirmationCount, "应为未分配总裁项目生成 PENDING 确认行");

        Long discrepancyCount = discrepancyLogMapper.selectCount(
                new LambdaQueryWrapper<DiscrepancyLog>()
                        .eq(DiscrepancyLog::getPeriodId, period.getPeriodId())
                        .eq(DiscrepancyLog::getProjectCode, "PRJ_NOPRES")
                        .eq(DiscrepancyLog::getType, "NO_PRESIDENT"));
        assertEquals(1, discrepancyCount, "未分配总裁的项目应写入 NO_PRESIDENT 差异");
    }

    // 功能：强制关闭（abort）——ONGOING 直接置为 COMPLETED
    @Test
    void shouldAbortPeriod() {
        AssessmentPeriod period = createTestPeriod("中止周期");
        startPeriod(period.getPeriodId());

        AssessmentPeriod aborted = periodService.abortPeriod(period.getPeriodId());
        assertEquals("COMPLETED", aborted.getStatus());
    }

    // 功能：PD 提交校准——CALIBRATING 周期写入当前时间，返回已带提交时间的周期
    @Test
    void shouldSubmitCalibration() {
        AssessmentPeriod period = createTestPeriod("提交校准");
        startPeriod(period.getPeriodId());
        periodService.enterCalibration(period.getPeriodId());

        AssessmentPeriod submitted = periodService.submitCalibration(period.getPeriodId());
        assertNotNull(submitted.getCalibrationSubmittedAt());
    }

    // 功能：非 CALIBRATING 周期提交校准被拒绝——400 业务异常
    @Test
    void shouldRejectSubmitCalibrationWhenNotCalibrating() {
        AssessmentPeriod period = createTestPeriod("非校准提交");
        startPeriod(period.getPeriodId()); // ONGOING

        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.submitCalibration(period.getPeriodId()));
        assertEquals(400, ex.getCode());
    }

    // 功能：总裁退回后 PD 重新提交——周期内 RETURNED 确认行重置为 PENDING（配合周期级「提交校准」按钮）
    @Test
    void shouldResetReturnedToPendingOnSubmitCalibration() {
        AssessmentPeriod period = createTestPeriod("重新提交校准");
        startPeriod(period.getPeriodId());
        periodService.enterCalibration(period.getPeriodId());

        ProjectConfirmation returned = new ProjectConfirmation();
        returned.setPeriodId(period.getPeriodId());
        returned.setProjectCode("PRJ_RESUBMIT");
        returned.setAssesseeId("EMP_RESUBMIT");
        returned.setStatus("RETURNED");
        returned.setReturnCount(1);
        returned.setReturnReason("结果有误");
        projectConfirmationMapper.insert(returned);

        periodService.submitCalibration(period.getPeriodId());

        ProjectConfirmation after = projectConfirmationMapper.selectById(returned.getId());
        assertEquals("PENDING", after.getStatus(), "重新提交后 RETURNED 应重置为 PENDING");
        assertNull(after.getReturnReason(), "重新提交后应清除退回原因");
    }

    // 功能：提交校准幂等——重复提交不报错且保留首次提交时间
    @Test
    void shouldBeIdempotentSubmitCalibration() {
        AssessmentPeriod period = createTestPeriod("幂等提交");
        startPeriod(period.getPeriodId());
        periodService.enterCalibration(period.getPeriodId());

        AssessmentPeriod first = periodService.submitCalibration(period.getPeriodId());
        AssessmentPeriod second = periodService.submitCalibration(period.getPeriodId());
        assertEquals(first.getCalibrationSubmittedAt(), second.getCalibrationSubmittedAt());
    }

    // 功能：结果可见性由周期态推导——仅 PUBLISHED/COMPLETED 可见；CONFIRMED 待发布不可见
    @Test
    void shouldDeriveResultVisibilityFromPeriodStatus() {
        AssessmentPeriod period = createTestPeriod("可见性周期");
        assertFalse(periodService.isResultVisible(period.getPeriodId())); // INIT

        startPeriod(period.getPeriodId());
        assertFalse(periodService.isResultVisible(period.getPeriodId())); // ONGOING

        periodService.enterCalibration(period.getPeriodId());
        assertFalse(periodService.isResultVisible(period.getPeriodId())); // CALIBRATING

        periodService.tryConfirmPeriod(period.getPeriodId());
        assertFalse(periodService.isResultVisible(period.getPeriodId())); // CONFIRMED 待发布不可见

        periodService.publishPeriod(period.getPeriodId());
        assertTrue(periodService.isResultVisible(period.getPeriodId())); // PUBLISHED 可见

        periodService.closePeriod(period.getPeriodId());
        assertTrue(periodService.isResultVisible(period.getPeriodId())); // COMPLETED 可见
    }

    // 辅助：插入员工
    private Employee seedEmployee(String employeeId) {
        Employee emp = new Employee();
        emp.setEmployeeId(employeeId);
        emp.setName("员工" + employeeId);
        emp.setEmail(employeeId + "@test.com");
        emp.setCategory("管理类");
        emp.setPosition("项目经理");
        emp.setOrgName("信息部");
        emp.setStatus("ACTIVE");
        employeeMapper.insert(emp);
        return emp;
    }
}
