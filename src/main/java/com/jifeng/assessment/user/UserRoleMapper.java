package com.jifeng.assessment.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    // 功能：物理删除某用户的全部角色——绕过全局逻辑删除(logic-delete-field=deleted)，
    // 避免 deleted=1 残留行积累导致 uk_user_role(user_id,role_type,deleted) 唯一约束冲突
    @Delete("DELETE FROM user_role WHERE user_id = #{userId}")
    int deletePhysicallyByUserId(@Param("userId") String userId);
}
