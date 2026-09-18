// 模块用途：考核任务状态机——使用枚举类型安全校验状态转换合法性
// 依赖文件：TaskStatus.java, TaskAction.java, BusinessException.java
// 修改注意：新增状态/动作需同步更新 TRANSITIONS 表；CONFIRMED/CANCELED 为终态无出边
package com.jifeng.assessment.task;

import com.jifeng.assessment.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class TaskStateMachine {

    // 允许的转换：当前状态 → (动作 → 目标状态)
    private static final Map<TaskStatus, Map<TaskAction, TaskStatus>> TRANSITIONS = new EnumMap<>(TaskStatus.class);

    static {
        // PENDING：待评分，可开始评分或取消
        Map<TaskAction, TaskStatus> pending = new EnumMap<>(TaskAction.class);
        pending.put(TaskAction.START, TaskStatus.IN_PROGRESS);
        pending.put(TaskAction.CANCEL, TaskStatus.CANCELED);
        TRANSITIONS.put(TaskStatus.PENDING, pending);

        // IN_PROGRESS：评分中，可提交、暂存草稿或取消
        Map<TaskAction, TaskStatus> inProgress = new EnumMap<>(TaskAction.class);
        inProgress.put(TaskAction.SUBMIT, TaskStatus.SUBMITTED);
        inProgress.put(TaskAction.SAVE_DRAFT, TaskStatus.IN_PROGRESS);
        inProgress.put(TaskAction.CANCEL, TaskStatus.CANCELED);
        TRANSITIONS.put(TaskStatus.IN_PROGRESS, inProgress);

        // SUBMITTED：已提交，PD 可确认
        Map<TaskAction, TaskStatus> submitted = new EnumMap<>(TaskAction.class);
        submitted.put(TaskAction.CONFIRM, TaskStatus.CONFIRMED);
        TRANSITIONS.put(TaskStatus.SUBMITTED, submitted);

        // CONFIRMED / CANCELED 为终态，无出边（TRANSITIONS 中不注册）
    }

    // 功能：校验并执行状态转换——非法转换抛异常
    public TaskStatus transition(TaskStatus current, TaskAction action) {
        Map<TaskAction, TaskStatus> allowed = TRANSITIONS.get(current);
        if (allowed == null || !allowed.containsKey(action)) {
            throw new BusinessException(400,
                    "不允许从 " + current + " 执行 " + action + " 操作");
        }
        return allowed.get(action);
    }
}
