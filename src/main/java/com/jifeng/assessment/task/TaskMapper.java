package com.jifeng.assessment.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskMapper extends BaseMapper<AssessmentTask> {

    // 评分与凭证写入共用任务行锁，提交后等待中的上传必须重新读取任务状态。
    @Select("SELECT * FROM assessment_task WHERE id = #{taskId} AND deleted = 0 FOR UPDATE")
    AssessmentTask selectByIdForUpdate(@Param("taskId") Long taskId);

    // 功能：幂等插入——联合唯一约束冲突时静默跳过（INSERT ON CONFLICT DO NOTHING）
    // 用途：增量生成考核任务时，同一员工+项目+考核人只生成一条，重复触发不报错
    @Insert("INSERT INTO assessment_task "
            + "(period_id, assessor_id, assessee_id, project_code, project_stage, task_type, "
            + " status, deleted, created_at, updated_at, version) "
            + "VALUES (#{periodId}, #{assessorId}, #{assesseeId}, #{projectCode}, #{projectStage}, #{taskType}, "
            + " #{status}, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0) "
            + "ON CONFLICT (period_id, assessor_id, assessee_id, project_code, project_stage, task_type, deleted) DO NOTHING")
    int insertIgnore(AssessmentTask task);
}
