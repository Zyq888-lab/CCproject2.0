// 模块用途：项目KPI指标配置实体——对应 project_kpi_config 表，含乐观锁和逻辑删除
// 依赖文件：无
// 修改注意：weight 为 DECIMAL(5,4)，Java 用 BigDecimal 精确表示
package com.jifeng.assessment.kpi;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("project_kpi_config")
public class ProjectKpiConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("project_role_code")
    private String projectRoleCode;

    @TableField("project_stage")
    private String projectStage;

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
