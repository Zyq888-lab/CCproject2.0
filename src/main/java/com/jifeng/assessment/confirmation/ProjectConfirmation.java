// 模块用途：项目确认实体——对应 project_confirmation 表，逐项目总裁确认状态机
// 依赖文件：无
// 修改注意：status: PENDING/APPROVED/RETURNED；确认单元 = (period_id, project_code) 唯一
package com.jifeng.assessment.confirmation;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project_confirmation")
public class ProjectConfirmation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("period_id")
    private String periodId;

    @TableField("project_code")
    private String projectCode;

    private String status;

    @TableField("return_count")
    private Integer returnCount;

    @TableField(value = "return_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String returnReason;

    @TableField("confirmed_by_employee_id")
    private String confirmedByEmployeeId;

    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
