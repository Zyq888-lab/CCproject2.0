// 模块用途：用户信息返回对象——不含密码哈希，包含关联的员工姓名和角色列表
// 依赖文件：SysUser.java, Employee.java
// 修改注意：增减字段时需同步更新 UserService 中的 toDTO 转换方法
package com.jifeng.assessment.user;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserDTO {
    private String userId;
    private String username;
    private String employeeId;
    private String employeeName;
    private Boolean enabled;
    private List<String> roles;
    private LocalDateTime createdAt;
}
