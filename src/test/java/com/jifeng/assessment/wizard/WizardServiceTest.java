// 模块用途：WizardService 单元测试——覆盖进度查询、分步完成、去重、重置
// 依赖文件：WizardService.java, WizardProgressMapper.java, WizardProgress.java
// 修改注意：每个测试用唯一userId避免数据干扰，步骤推进逻辑见completeStep
package com.jifeng.assessment.wizard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WizardServiceTest {

    @Autowired
    private WizardService wizardService;

    private String uniqueUserId() {
        return "test-user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // 功能：新用户查询进度返回currentStep=0，completed=false
    @Test
    void shouldReturnNeverStartedForNewUser() {
        WizardProgressDTO progress = wizardService.getProgress(uniqueUserId());
        assertEquals(0, progress.getCurrentStep());
        assertEquals("", progress.getCompletedSteps());
        assertFalse(progress.isCompleted());
    }

    // 功能：完成步骤1后currentStep推进到2
    @Test
    void shouldCompleteStepAndAdvance() {
        String userId = uniqueUserId();
        WizardProgressDTO result = wizardService.completeStep(1, userId);
        assertEquals(2, result.getCurrentStep());
        assertTrue(result.getCompletedSteps().contains("1"));
        assertFalse(result.isCompleted());
    }

    // 功能：重复完成同一步骤时completedSteps去重，不重复记录
    @Test
    void shouldDedupCompletedSteps() {
        String userId = uniqueUserId();
        wizardService.completeStep(1, userId);
        WizardProgressDTO result = wizardService.completeStep(1, userId);
        // 去重后 "1" 仍只出现一次
        assertEquals(1, result.getCompletedSteps().split(",").length);
        assertTrue(result.getCompletedSteps().contains("1"));
        assertEquals(2, result.getCurrentStep());
    }

    // 功能：仅完成第7步（未完成1-6步）不标记向导完成——需全部7步去重完成
    @Test
    void shouldNotMarkCompletedWhenOnlyStep7Done() {
        String userId = uniqueUserId();
        WizardProgressDTO result = wizardService.completeStep(7, userId);
        assertEquals(7, result.getCurrentStep());
        assertFalse(result.isCompleted());
    }

    // 功能：逐步完成1-6步后，第7步完成时向导标记完成
    @Test
    void shouldCompleteAllStepsSequentially() {
        String userId = uniqueUserId();
        for (int i = 1; i <= 7; i++) {
            wizardService.completeStep(i, userId);
        }
        WizardProgressDTO progress = wizardService.getProgress(userId);
        assertEquals(7, progress.getCurrentStep());
        assertTrue(progress.isCompleted());
        // completedSteps 应包含 1-7 全部
        String[] parts = progress.getCompletedSteps().split(",");
        assertEquals(7, parts.length);
    }

    // 功能：无效步骤（0或8）抛出IllegalArgumentException
    @Test
    void shouldRejectInvalidStep() {
        String userId = uniqueUserId();
        assertThrows(IllegalArgumentException.class,
                () -> wizardService.completeStep(0, userId));
        assertThrows(IllegalArgumentException.class,
                () -> wizardService.completeStep(8, userId));
    }

    // 功能：部分完成步骤后查询进度，currentStep指向下一步
    @Test
    void shouldGetProgressAfterPartialCompletion() {
        String userId = uniqueUserId();
        wizardService.completeStep(1, userId);
        wizardService.completeStep(2, userId);
        wizardService.completeStep(3, userId);

        WizardProgressDTO progress = wizardService.getProgress(userId);
        assertEquals(4, progress.getCurrentStep());
        assertEquals("1,2,3", progress.getCompletedSteps());
        assertFalse(progress.isCompleted());
    }

    // 功能：重置向导后进度回到初始状态
    @Test
    void shouldResetProgress() {
        String userId = uniqueUserId();
        wizardService.completeStep(1, userId);
        wizardService.completeStep(2, userId);

        wizardService.reset(userId);

        WizardProgressDTO progress = wizardService.getProgress(userId);
        assertEquals(0, progress.getCurrentStep());
        assertEquals("", progress.getCompletedSteps());
        assertFalse(progress.isCompleted());
    }

    // 功能：跳步骤完成（如直接完成第3步），验证currentStep推进到step+1
    @Test
    void shouldAdvanceToStepPlusOne() {
        String userId = uniqueUserId();
        WizardProgressDTO result = wizardService.completeStep(5, userId);
        assertEquals(6, result.getCurrentStep());
        assertTrue(result.getCompletedSteps().contains("5"));
    }
}
