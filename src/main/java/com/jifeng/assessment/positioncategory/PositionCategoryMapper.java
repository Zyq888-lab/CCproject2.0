// 模块用途：岗位分类 Mapper——MyBatis-Plus BaseMapper + 自定义查询绕过逻辑删除过滤
// 依赖文件：PositionCategory.java
// 修改注意：findByNameIgnoreDeleted 用于 checkNameUnique 含逻辑删除校验
package com.jifeng.assessment.positioncategory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PositionCategoryMapper extends BaseMapper<PositionCategory> {

    @Select("SELECT * FROM position_category WHERE name = #{name}")
    List<PositionCategory> findByNameIgnoreDeleted(@Param("name") String name);
}
