// 模块用途：PeriodService 单元测试——覆盖CRUD、活跃周期唯一约束、关闭周期
// 依赖文件：PeriodService.java, AssessmentPeriod.java, PeriodMapper.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.period;

import com.jifeng.assessment.common.BusinessException;
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

    // 辅助方法：创建测试周期
    private AssessmentPeriod createTestPeriod(String name) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodName(name);
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 6, 30));
        return periodService.createPeriod(period);
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
        periodService.closePeriod(p1.getPeriodId());
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
        periodService.closePeriod(p1.getPeriodId());
        createTestPeriod("周期B");  // status=INIT

        List<AssessmentPeriod> completed = periodService.listPeriods("COMPLETED");
        assertEquals(1, completed.size());
        assertEquals("COMPLETED", completed.get(0).getStatus());

        List<AssessmentPeriod> init = periodService.listPeriods("INIT");
        assertEquals(1, init.size());
        assertEquals("INIT", init.get(0).getStatus());
    }

    // 功能：关闭考核周期——状态变为COMPLETED
    @Test
    void shouldClosePeriod() {
        AssessmentPeriod period = createTestPeriod("2026年上半年考核");
        assertEquals("INIT", period.getStatus());

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
        periodService.closePeriod(first.getPeriodId());

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
}
