// 模块用途：角色类型枚举——定义系统预定义角色 ADMIN/总裁/PD/PM/评估人/员工
// 依赖文件：无
// 修改注意：新增角色类型需同步更新数据库 user_role 表中的 role_type 值
package com.jifeng.assessment.user;

public enum RoleType {
    ADMIN("ADMIN", "管理员"),
    PRESIDENT("总裁", "总裁"),
    PD("PD", "PD负责人"),
    PM("PM", "项目经理"),
    ASSESSOR("评估人", "评估人"),
    EMPLOYEE("员工", "员工");

    private final String code;
    private final String displayName;

    RoleType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    // 功能：根据角色代码查找枚举值，找不到返回 null
    public static RoleType fromCode(String code) {
        for (RoleType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
