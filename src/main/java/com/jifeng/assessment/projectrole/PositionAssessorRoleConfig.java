// 模块用途：岗位考核人角色关联实体——仅用于删除角色时的引用检查
// 依赖文件：无
// 修改注意：此实体仅用于 selectCount 查询，不做增删改
package com.jifeng.assessment.projectrole;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("position_assessor_role_config")
public class PositionAssessorRoleConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long positionConfigId;
    private String roleCode;
    private Integer deleted;
}
