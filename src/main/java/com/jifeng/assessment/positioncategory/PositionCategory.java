// 模块用途：岗位分类实体——对应 position_category 表，含乐观锁和逻辑删除
// 依赖文件：无
// 修改注意：sort_order 映射为 sortOrder，MyBatis-Plus 自动处理 created_at/updated_at 映射
package com.jifeng.assessment.positioncategory;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("position_category")
public class PositionCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableLogic
    private Integer deleted;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
