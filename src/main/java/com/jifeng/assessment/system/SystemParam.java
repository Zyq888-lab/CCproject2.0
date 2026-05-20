// 模块用途：系统参数实体——对应 system_param 表，key-value 配置，含乐观锁
// 依赖文件：无
// 修改注意：param_key 有唯一约束 uk_system_param_key
package com.jifeng.assessment.system;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("system_param")
public class SystemParam {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("param_key")
    private String paramKey;

    @TableField("param_value")
    private String paramValue;

    private String description;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
