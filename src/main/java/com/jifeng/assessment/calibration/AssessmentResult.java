// 模块用途：考核结果实体——对应 assessment_result 表，员工×周期一行，含乐观锁和逻辑删除
// 依赖文件：无
// 修改注意：original_score/adjusted_score 为 composite 总分（0-5 分制，4 位小数），
//   与 ScoreCalculator 输出精度一致；改分对象是 composite 总分，不落在 assessment_score 指标分行
package com.jifeng.assessment.calibration;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("assessment_result")
public class AssessmentResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("period_id")
    private String periodId;

    @TableField("assessee_id")
    private String assesseeId;

    @TableField("original_score")
    private BigDecimal originalScore;

    @TableField("adjusted_score")
    private BigDecimal adjustedScore;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
