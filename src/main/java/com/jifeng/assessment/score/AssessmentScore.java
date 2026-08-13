// 模块用途：考核评分实体——对应 assessment_score 表，含乐观锁和逻辑删除
// 依赖文件：无
// 修改注意：kpi_config_id 多态引用 project_kpi_config 或 func_kpi_config，由 kpi_type 区分
package com.jifeng.assessment.score;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("assessment_score")
public class AssessmentScore {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private Long taskId;

    @TableField("kpi_config_id")
    private Long kpiConfigId;

    @TableField("kpi_type")
    private String kpiType;

    private BigDecimal score;

    @TableField("evidence_url")
    private String evidenceUrl;

    private String status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
