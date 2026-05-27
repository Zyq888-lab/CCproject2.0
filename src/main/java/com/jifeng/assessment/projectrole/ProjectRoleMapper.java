package com.jifeng.assessment.projectrole;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectRoleMapper extends BaseMapper<ProjectRole> {

    @Select("SELECT role_code, role_name, description, is_active, deleted, created_at, updated_at, version FROM project_role WHERE role_code=#{roleCode}")
    ProjectRole selectByIdBypassDelete(String roleCode);

    @Update("UPDATE project_role SET role_name=#{roleName}, description=#{description}, is_active=#{isActive}, deleted=0, updated_at=CURRENT_TIMESTAMP WHERE role_code=#{roleCode}")
    int reviveDeleted(ProjectRole role);
}
