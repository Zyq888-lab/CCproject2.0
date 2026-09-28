package com.jifeng.assessment.calibration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface AssessmentResultMapper extends BaseMapper<AssessmentResult> {

    // 功能：原子 upsert 结果行——INSERT ... ON CONFLICT 兜底并发生成，避免唯一键冲突 500
    // 幂等：有人工总分改分审计的行保留 adjusted_score；否则使用最新逐项 KPI 校准结果。
    //   version 自增与 @Version 乐观锁口径一致
    @Insert("INSERT INTO assessment_result "
            + "(period_id, assessee_id, original_score, adjusted_score, deleted, created_at, updated_at, version) "
            + "VALUES (#{periodId}, #{assesseeId}, #{originalScore}, #{calibratedScore}, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0) "
            + "ON CONFLICT (assessee_id, period_id, deleted) DO UPDATE SET "
            + "original_score = EXCLUDED.original_score, "
            + "adjusted_score = CASE WHEN EXISTS (SELECT 1 FROM score_adjustment sa "
            + "WHERE sa.assessment_result_id = assessment_result.id) "
            + "THEN assessment_result.adjusted_score ELSE EXCLUDED.adjusted_score END, "
            + "updated_at = CURRENT_TIMESTAMP, "
            + "version = assessment_result.version + 1")
    int upsertResult(@Param("periodId") String periodId,
                     @Param("assesseeId") String assesseeId,
                     @Param("originalScore") BigDecimal originalScore,
                     @Param("calibratedScore") BigDecimal calibratedScore);
}
