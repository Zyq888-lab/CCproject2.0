// 模块用途：考核任务状态枚举——对应 assessment_task.status 的 6 个合法值
// 依赖文件：无
// 修改注意：新增状态需同步更新 TaskStateMachine 的 TRANSITIONS 转换表
package com.jifeng.assessment.task;

public enum TaskStatus {
    PENDING,       // 待评分（系统生成后的初始状态）
    IN_PROGRESS,   // 评分中/草稿
    SUBMITTED,     // 已提交
    RETURNED,      // PD退回
    CONFIRMED,     // 已确认（终态）
    CANCELED       // 已取消（终态）
}
