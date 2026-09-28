// 模块用途：员工账号总览返回对象——以员工为行、左连接 sys_user 回填账号激活状态与角色
// 依赖文件：Employee.java, SysUser.java
// 修改注意：activated=false 时 userId/username/enabled 为 null、roles 为空列表，前端据此区分已激活/未激活行
package com.jifeng.assessment.user;

import lombok.Data;

import java.util.List;

@Data
public class EmployeeAccountDTO {

    /** 员工工号 */
    private String employeeId;
    private String name;
    private String email;
    private String category;
    private String position;
    private String orgName;
    private String directLeaderId;

    /** 员工在职状态（ACTIVE=在职 / INACTIVE=离职） */
    private String status;

    /** 是否已激活账号（存在 sys_user 记录） */
    private Boolean activated;

    /** 已激活时的系统用户信息（未激活为 null） */
    private String userId;
    private String username;
    private Boolean enabled;
    private List<String> roles;
}
