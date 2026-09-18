// 模块用途：INIT→ONGOING 并发翻转集成测试——验证原子 UPDATE WHERE status='INIT' 下并发只成功一次
// 依赖文件：PeriodMapper.java, AssessmentPeriod.java
// 修改注意：无 @Transactional（并发线程需各自独立连接读取已提交状态），finally 软删清理避免污染
package com.jifeng.assessment.period;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class PeriodStatusConcurrencyTest {

    @Autowired
    private PeriodMapper periodMapper;

    private static final String PERIOD_ID = "PERIOD-CONC";

    // 功能：两个线程并发 INIT→ONGOING，原子 UPDATE 下恰好一个命中 status='INIT'（返回 1 行），
    //   另一个 0 行；最终状态唯一为 ONGOING（与 enterCalibration/confirm/close/abort 同口径）
    @Test
    void concurrentInitToOngoingShouldFlipOnlyOnce() throws Exception {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(PERIOD_ID);
        period.setPeriodName("并发翻转测试周期");
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 6, 30));
        period.setStatus("INIT");
        periodMapper.insert(period);

        try {
            int threads = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return periodMapper.updateStatus(PERIOD_ID, "INIT", "ONGOING");
                }));
            }
            ready.await();
            go.countDown();

            int successCount = 0;
            for (Future<Integer> f : futures) {
                successCount += f.get();
            }
            pool.shutdown();

            assertEquals(1, successCount, "并发下 INIT→ONGOING 应恰好成功一次");
            assertEquals("ONGOING", periodMapper.selectById(PERIOD_ID).getStatus());
        } finally {
            periodMapper.deleteById(PERIOD_ID); // 软删清理，避免污染其他用例的活跃周期唯一约束
        }
    }
}
