// 模块用途：职能KPI指标配置实体——对应 func_kpi_config 表，含乐观锁和逻辑删除
// 依赖文件：无
// 修改注意：category + position 组合对应岗位配置维度，weight 为 DECIMAL(5,4)
package com.jifeng.assessment.kpi;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("func_kpi_config")
public class FuncKpiConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String category;
    private String position;

    @TableField("kpi_name")
    private String kpiName;

    @TableField("evaluation_criteria")
    private String evaluationCriteria;

    private BigDecimal weight;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("is_active")
    private Boolean isActive;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
