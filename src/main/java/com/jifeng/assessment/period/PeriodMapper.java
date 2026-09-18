package com.jifeng.assessment.period;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PeriodMapper extends BaseMapper<AssessmentPeriod> {

    // 功能：原子状态翻转——仅当周期当前处于 fromStatus 时更新为 toStatus，返回受影响行数
    // 用途：状态机单向迁移的并发安全（总裁确认等关键翻转不依赖 read-modify-write）
    @Update("UPDATE assessment_period SET status = #{toStatus}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE period_id = #{periodId} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("periodId") String periodId,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    // 功能：强制关闭（abort）——任意非COMPLETED状态直接置为COMPLETED，返回受影响行数
    @Update("UPDATE assessment_period SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP "
            + "WHERE period_id = #{periodId} AND status <> 'COMPLETED' AND deleted = 0")
    int forceComplete(@Param("periodId") String periodId);

    // 功能：物理删除周期——绕过 @TableLogic 软删，用于测试清理固定主键的残留行，
    //   避免 deleteById 软删(置 deleted=1)后物理行残留导致下次插入主键冲突
    @Delete("DELETE FROM assessment_period WHERE period_id = #{periodId}")
    int physicalDelete(@Param("periodId") String periodId);
}
