// 模块用途：考核任务操作枚举——状态机转换的触发动作
// 依赖文件：无
// 修改注意：新增动作需同步更新 TaskStateMachine 的 TRANSITIONS 转换表
package com.jifeng.assessment.task;

public enum TaskAction {
    START,       // 开始评分 PENDING → IN_PROGRESS
    SUBMIT,      // 提交评分 IN_PROGRESS → SUBMITTED
    SAVE_DRAFT,  // 暂存草稿 IN_PROGRESS → IN_PROGRESS（不变）
    CANCEL,      // 取消 PENDING/IN_PROGRESS → CANCELED
    CONFIRM      // 确认 SUBMITTED → CONFIRMED
}
