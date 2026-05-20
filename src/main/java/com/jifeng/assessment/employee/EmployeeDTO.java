// 模块用途：员工信息返回对象——不含逻辑删除标记deleted字段，用于前端展示
// 依赖文件：Employee.java
// 修改注意：增减字段时需同步更新 EmployeeService 中的 toDTO 转换方法
package com.jifeng.assessment.employee;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class EmployeeDTO {
    private String employeeId;
    private String name;
    private String email;
    private String category;
    private String position;
    private String orgName;
    private String directLeaderId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
