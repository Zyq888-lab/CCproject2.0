// 模块用途：项目阶段枚举——定义项目所处阶段 P2/P3/P4/P5
// 依赖文件：无
// 修改注意：新增阶段值需同步更新 project 表的 project_stage 字段校验
package com.jifeng.assessment.project;

public enum ProjectStage {
    P2("P2", "P2阶段"),
    P3("P3", "P3阶段"),
    P4("P4", "P4阶段"),
    P5("P5", "P5阶段");

    private final String code;
    private final String displayName;

    ProjectStage(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    // 功能：根据阶段代码查找枚举值，找不到返回 null
    public static ProjectStage fromCode(String code) {
        for (ProjectStage stage : values()) {
            if (stage.code.equals(code)) {
                return stage;
            }
        }
        return null;
    }
}
