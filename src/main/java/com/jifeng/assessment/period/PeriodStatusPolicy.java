// 模块用途：周期状态策略——收敛「参与可写」「结果可见」「监控审批节点映射」三类状态判断为单一方法，
//   新增状态（如 PUBLISHED）只改这里，避免各调用点漏改
// 依赖文件：无
// 修改注意：assessment_period.status 为 VARCHAR 无 CHECK；六态 INIT/ONGOING/CALIBRATING/CONFIRMED/PUBLISHED/COMPLETED
package com.jifeng.assessment.period;

public final class PeriodStatusPolicy {

    public static final String INIT = "INIT";
    public static final String ONGOING = "ONGOING";
    public static final String CALIBRATING = "CALIBRATING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String COMPLETED = "COMPLETED";

    private PeriodStatusPolicy() {
    }

    // 功能：参与可写——仅 INIT/ONGOING 放行；其余（CALIBRATING/CONFIRMED/PUBLISHED/COMPLETED）一律冻结
    public static boolean isParticipatable(String status) {
        return INIT.equals(status) || ONGOING.equals(status);
    }

    // 功能：结果可见——仅 PUBLISHED（已发布）/COMPLETED（已归档）员工可见最终结果；CONFIRMED 待发布不可见
    public static boolean isResultVisible(String status) {
        return PUBLISHED.equals(status) || COMPLETED.equals(status);
    }

    // 功能：监控审批节点映射——六态全覆盖，返回周期级审批节点阶段：
    //   INIT/ONGOING→TASK（任务级：评分阶段=评估人，提交后=PD）；CALIBRATING→CALIBRATION（PD待校准/总裁待确认）；
    //   CONFIRMED/PUBLISHED/COMPLETED→终态（无当前审批人）
    public static String approvalNode(String status) {
        switch (status) {
            case INIT:
            case ONGOING:
                return "TASK";
            case CALIBRATING:
                return "CALIBRATION";
            case CONFIRMED:
                return "CONFIRMED";
            case PUBLISHED:
                return "PUBLISHED";
            case COMPLETED:
                return "COMPLETED";
            default:
                return "TASK";
        }
    }
}
