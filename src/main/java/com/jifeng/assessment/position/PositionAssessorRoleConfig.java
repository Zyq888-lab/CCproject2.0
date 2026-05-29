// 模块用途：岗位考核人角色关联实体——对应 position_assessor_role_config 表
// 依赖文件：无
// 修改注意：positionConfigId 关联 position_assessment_config.id，roleCode 关联 project_role.role_code
package com.jifeng.assessment.position;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("position_assessor_role_config")
public class PositionAssessorRoleConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("position_config_id")
    private Long positionConfigId;

    private String roleCode;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @TableField(exist = false)
    private String roleName;
}
