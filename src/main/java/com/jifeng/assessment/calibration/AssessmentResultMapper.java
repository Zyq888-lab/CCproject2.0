package com.jifeng.assessment.calibration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface AssessmentResultMapper extends BaseMapper<AssessmentResult> {

    // 功能：原子 upsert 结果行——INSERT ... ON CONFLICT 兜底并发生成，避免唯一键冲突 500
    // 幂等：已手动改分(original≠adjusted)的行仅刷新 original_score，保留 adjusted_score；
    //   version 自增与 @Version 乐观锁口径一致
    @Insert("INSERT INTO assessment_result "
            + "(period_id, assessee_id, original_score, adjusted_score, deleted, created_at, updated_at, version) "
            + "VALUES (#{periodId}, #{assesseeId}, #{score}, #{score}, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0) "
            + "ON CONFLICT (assessee_id, period_id, deleted) DO UPDATE SET "
            + "original_score = EXCLUDED.original_score, "
            + "adjusted_score = CASE WHEN assessment_result.original_score = assessment_result.adjusted_score "
            + "THEN EXCLUDED.adjusted_score ELSE assessment_result.adjusted_score END, "
            + "updated_at = CURRENT_TIMESTAMP, "
            + "version = assessment_result.version + 1")
    int upsertResult(@Param("periodId") String periodId,
                     @Param("assesseeId") String assesseeId,
                     @Param("score") BigDecimal score);
}
