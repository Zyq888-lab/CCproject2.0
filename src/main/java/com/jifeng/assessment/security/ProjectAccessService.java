package com.jifeng.assessment.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private final ProjectRoleAssignmentMapper assignmentMapper;
    private final SysUserMapper userMapper;

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }

    public String currentEmployeeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        return user == null ? null : user.getEmployeeId();
    }

    public boolean isPrimaryRole(String projectCode, String projectStage, String roleCode) {
        String globalRole = "PRESIDENT".equals(roleCode) ? "总裁" : roleCode;
        String employeeId = currentEmployeeId();
        if (!hasRole(globalRole) || !StringUtils.hasText(employeeId)
                || !StringUtils.hasText(projectCode) || !StringUtils.hasText(projectStage)) {
            return false;
        }
        Long count = assignmentMapper.selectCount(new LambdaQueryWrapper<ProjectRoleAssignment>()
                .eq(ProjectRoleAssignment::getProjectCode, projectCode)
                .eq(ProjectRoleAssignment::getProjectStage, projectStage)
                .eq(ProjectRoleAssignment::getProjectRoleCode, roleCode)
                .eq(ProjectRoleAssignment::getEmployeeId, employeeId)
                .eq(ProjectRoleAssignment::getIsPrimary, true)
                .eq(ProjectRoleAssignment::getDeleted, 0));
        return count != null && count > 0;
    }

    public void assertPrimaryRole(String projectCode, String projectStage, String roleCode) {
        if (!isAdmin() && !isPrimaryRole(projectCode, projectStage, roleCode)) {
            throw new BusinessException(403, "无权操作该项目阶段");
        }
    }

    private boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }
}
