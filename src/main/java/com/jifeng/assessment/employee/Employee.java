package com.jifeng.assessment.employee;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("employee")
public class Employee {
    @TableId
    private String employeeId;
    private String name;
    private String email;
    private String category;
    private String position;
    private String orgName;
    private String directLeaderId;
    private String status;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
