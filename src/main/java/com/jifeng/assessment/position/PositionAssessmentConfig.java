// 模块用途：岗位考核配置实体——对应 position_assessment_config 表，含乐观锁和逻辑删除
// 依赖文件：无
// 修改注意：projectWeight/funcWeight 为 DECIMAL(5,4)，Java 用 BigDecimal 精确表示
package com.jifeng.assessment.position;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("position_assessment_config")
public class PositionAssessmentConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String category;
    private String position;

    @TableField("is_project_based")
    private Boolean isProjectBased;

    @TableField("default_project_role")
    private String defaultProjectRole;

    @TableField("func_assess_mode")
    private String funcAssessMode;

    private BigDecimal projectWeight;
    private BigDecimal funcWeight;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
