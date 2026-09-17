// 模块用途：改分审计实体——对应 score_adjustment 审计表（追加式，不可变）
// 依赖文件：无
// 修改注意：改键 assessment_result_id（不是 task_id/score_id）；无逻辑删除与乐观锁（审计行只增不改）
package com.jifeng.assessment.result;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("score_adjustment")
public class ScoreAdjustment {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("assessment_result_id")
    private Long assessmentResultId;

    @TableField("adjusted_by")
    private String adjustedBy;

    @TableField("old_score")
    private BigDecimal oldScore;

    @TableField("new_score")
    private BigDecimal newScore;

    private String reason;

    private LocalDateTime createdAt;
}
