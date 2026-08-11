package com.jifeng.assessment.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    default Project selectByCodeAndStage(String projectCode, String projectStage) {
        return selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getProjectCode, projectCode)
                .eq(Project::getProjectStage, projectStage));
    }

    @Select("SELECT * FROM project WHERE project_code = #{projectCode} AND deleted = 0 ORDER BY project_stage")
    List<Project> selectByCode(String projectCode);
}
