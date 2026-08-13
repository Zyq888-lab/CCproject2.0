// 模块用途：TaskStateMachine 单元测试——覆盖全部合法转换、非法转换、RETURN 超限自动 CONFIRMED
// 依赖文件：TaskStateMachine.java, TaskStatus.java, TaskAction.java
// 修改注意：纯单元测试，不依赖 Spring 上下文，直接 new 状态机实例
package com.jifeng.assessment.task;

import com.jifeng.assessment.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskStateMachineTest {

    private final TaskStateMachine stateMachine = new TaskStateMachine();

    // 功能：全部合法转换路径——PENDING→IN_PROGRESS→SUBMITTED→RETURNED→SUBMITTED→CONFIRMED
    @Test
    void shouldAllowAllValidTransitions() {
        // PENDING → IN_PROGRESS (START)
        assertEquals(TaskStatus.IN_PROGRESS,
                stateMachine.transition(TaskStatus.PENDING, TaskAction.START, 0, 3));

        // IN_PROGRESS → SUBMITTED (SUBMIT)
        assertEquals(TaskStatus.SUBMITTED,
                stateMachine.transition(TaskStatus.IN_PROGRESS, TaskAction.SUBMIT, 0, 3));

        // SUBMITTED → RETURNED (RETURN，未超限)
        assertEquals(TaskStatus.RETURNED,
                stateMachine.transition(TaskStatus.SUBMITTED, TaskAction.RETURN, 0, 3));

        // RETURNED → SUBMITTED (RESUBMIT)
        assertEquals(TaskStatus.SUBMITTED,
                stateMachine.transition(TaskStatus.RETURNED, TaskAction.RESUBMIT, 1, 3));

        // SUBMITTED → CONFIRMED (CONFIRM)
        assertEquals(TaskStatus.CONFIRMED,
                stateMachine.transition(TaskStatus.SUBMITTED, TaskAction.CONFIRM, 1, 3));

        // IN_PROGRESS → IN_PROGRESS (SAVE_DRAFT 不变)
        assertEquals(TaskStatus.IN_PROGRESS,
                stateMachine.transition(TaskStatus.IN_PROGRESS, TaskAction.SAVE_DRAFT, 0, 3));

        // PENDING → CANCELED (CANCEL)
        assertEquals(TaskStatus.CANCELED,
                stateMachine.transition(TaskStatus.PENDING, TaskAction.CANCEL, 0, 3));

        // SUBMITTED → IN_PROGRESS (WITHDRAW 评估人撤回)
        assertEquals(TaskStatus.IN_PROGRESS,
                stateMachine.transition(TaskStatus.SUBMITTED, TaskAction.WITHDRAW, 0, 3));
    }

    // 功能：非法转换——终态 CONFIRMED 无出边，任何动作都应抛异常
    @Test
    void shouldRejectInvalidTransition() {
        // CONFIRMED 是终态，不允许任何操作
        BusinessException ex = assertThrows(BusinessException.class,
                () -> stateMachine.transition(TaskStatus.CONFIRMED, TaskAction.START, 0, 3));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不允许"));

        // PENDING 不允许 SUBMIT（未开始评分不能提交）
        assertThrows(BusinessException.class,
                () -> stateMachine.transition(TaskStatus.PENDING, TaskAction.SUBMIT, 0, 3));

        // IN_PROGRESS 不允许 RETURN（未提交不能退回）
        assertThrows(BusinessException.class,
                () -> stateMachine.transition(TaskStatus.IN_PROGRESS, TaskAction.RETURN, 0, 3));

        // CANCELED 是终态，不允许任何操作
        assertThrows(BusinessException.class,
                () -> stateMachine.transition(TaskStatus.CANCELED, TaskAction.RESUBMIT, 0, 3));
    }

    // 功能：RETURN 退回超限——returnCount >= maxReturns 时自动转为 CONFIRMED（标记争议）
    @Test
    void shouldAutoConfirmWhenReturnLimitExceeded() {
        // returnCount=3, maxReturns=3 → 超限，RETURN 应转为 CONFIRMED 而非 RETURNED
        assertEquals(TaskStatus.CONFIRMED,
                stateMachine.transition(TaskStatus.SUBMITTED, TaskAction.RETURN, 3, 3));

        // returnCount=5, maxReturns=3 → 已超限
        assertEquals(TaskStatus.CONFIRMED,
                stateMachine.transition(TaskStatus.SUBMITTED, TaskAction.RETURN, 5, 3));

        // returnCount=2, maxReturns=3 → 未超限，正常 RETURNED
        assertEquals(TaskStatus.RETURNED,
                stateMachine.transition(TaskStatus.SUBMITTED, TaskAction.RETURN, 2, 3));
    }
}
