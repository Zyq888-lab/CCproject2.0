// 模块用途：TaskStateMachine 单元测试——覆盖全部合法转换与非法转换
// 依赖文件：TaskStateMachine.java, TaskStatus.java, TaskAction.java
// 修改注意：纯单元测试，不依赖 Spring 上下文，直接 new 状态机实例
package com.jifeng.assessment.task;

import com.jifeng.assessment.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskStateMachineTest {

    private final TaskStateMachine stateMachine = new TaskStateMachine();

    // 功能：全部合法转换路径——PENDING→IN_PROGRESS→SUBMITTED→CONFIRMED
    @Test
    void shouldAllowAllValidTransitions() {
        // PENDING → IN_PROGRESS (START)
        assertEquals(TaskStatus.IN_PROGRESS,
                stateMachine.transition(TaskStatus.PENDING, TaskAction.START));

        // IN_PROGRESS → SUBMITTED (SUBMIT)
        assertEquals(TaskStatus.SUBMITTED,
                stateMachine.transition(TaskStatus.IN_PROGRESS, TaskAction.SUBMIT));

        // SUBMITTED → CONFIRMED (CONFIRM)
        assertEquals(TaskStatus.CONFIRMED,
                stateMachine.transition(TaskStatus.SUBMITTED, TaskAction.CONFIRM));

        // IN_PROGRESS → IN_PROGRESS (SAVE_DRAFT 不变)
        assertEquals(TaskStatus.IN_PROGRESS,
                stateMachine.transition(TaskStatus.IN_PROGRESS, TaskAction.SAVE_DRAFT));

        // PENDING → CANCELED (CANCEL)
        assertEquals(TaskStatus.CANCELED,
                stateMachine.transition(TaskStatus.PENDING, TaskAction.CANCEL));
    }

    // 功能：非法转换——终态无出边、未提交不能确认等，任何非法动作都应抛 400
    @Test
    void shouldRejectInvalidTransition() {
        // CONFIRMED 是终态，不允许任何操作
        BusinessException ex = assertThrows(BusinessException.class,
                () -> stateMachine.transition(TaskStatus.CONFIRMED, TaskAction.START));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不允许"));

        // PENDING 不允许 SUBMIT（未开始评分不能提交）
        assertThrows(BusinessException.class,
                () -> stateMachine.transition(TaskStatus.PENDING, TaskAction.SUBMIT));

        // SUBMITTED 不允许 START（已提交不能重新开始评分）
        assertThrows(BusinessException.class,
                () -> stateMachine.transition(TaskStatus.SUBMITTED, TaskAction.START));

        // CANCELED 是终态，不允许任何操作
        assertThrows(BusinessException.class,
                () -> stateMachine.transition(TaskStatus.CANCELED, TaskAction.CONFIRM));
    }
}
