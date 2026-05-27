// 模块用途：项目角色管理业务逻辑——CRUD、删除前引用检查、乐观锁更新、启用停用
// 依赖文件：ProjectRoleMapper.java, ProjectRole.java, PositionAssessorRoleConfigMapper.java
// 修改注意：role_code 不可修改，引用检查规则不变
package com.jifeng.assessment.projectrole;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectRoleService extends BaseService<ProjectRoleMapper, ProjectRole> {

    private final PositionAssessorRoleConfigMapper assessorRoleConfigMapper;
    private final ProjectRoleAssignmentMapper roleAssignmentMapper;
    private final ProjectKpiMapper projectKpiMapper;

    // 功能：查询所有项目角色，支持按 isActive 过滤
    public List<ProjectRoleDTO> listProjectRoles(Boolean isActive) {
        LambdaQueryWrapper<ProjectRole> wrapper = new LambdaQueryWrapper<>();
        if (isActive != null) {
            wrapper.eq(ProjectRole::getIsActive, isActive);
        }
        wrapper.orderByAsc(ProjectRole::getRoleCode);
        return baseMapper.selectList(wrapper).stream()
                .map(this::toDTO)
                .toList();
    }

    // 功能：新增项目角色——校验 roleCode 非空、不重复；支持逻辑删除后重建
    @Transactional
    public ProjectRoleDTO createProjectRole(ProjectRole role) {
        if (!StringUtils.hasText(role.getRoleCode())) {
            throw new BusinessException(400, "角色代码不能为空");
        }
        if (!StringUtils.hasText(role.getRoleName())) {
            throw new BusinessException(400, "角色名称不能为空");
        }
        if (baseMapper.selectById(role.getRoleCode()) != null) {
            throw new BusinessException(409, "角色代码" + role.getRoleCode() + "已存在");
        }
        role.setIsActive(role.getIsActive() != null ? role.getIsActive() : true);
        ProjectRole deleted = baseMapper.selectByIdBypassDelete(role.getRoleCode());
        if (deleted != null) {
            baseMapper.reviveDeleted(role);
            return toDTO(baseMapper.selectById(role.getRoleCode()));
        }
        baseMapper.insert(role);
        return toDTO(role);
    }

    // 功能：修改角色名称/描述/版本——使用乐观锁防止并发覆盖，roleCode不可修改
    @Transactional
    public ProjectRoleDTO updateProjectRole(String roleCode, ProjectRole update) {
        ProjectRole existing = baseMapper.selectById(roleCode);
        if (existing == null) {
            throw new BusinessException(404, "角色不存在: " + roleCode);
        }
        if (StringUtils.hasText(update.getRoleName())) {
            existing.setRoleName(update.getRoleName());
        }
        if (update.getDescription() != null) {
            existing.setDescription(update.getDescription());
        }
        if (update.getIsActive() != null) {
            existing.setIsActive(update.getIsActive());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(existing);
        return toDTO(baseMapper.selectById(roleCode));
    }

    // 功能：删除角色——检查 position_assessor_role_config 引用，如有引用则拒绝删除
    @Transactional
    public void deleteProjectRole(String roleCode) {
        ProjectRole existing = baseMapper.selectById(roleCode);
        if (existing == null) {
            throw new BusinessException(404, "角色不存在: " + roleCode);
        }

        LambdaQueryWrapper<PositionAssessorRoleConfig> refWrapper = new LambdaQueryWrapper<>();
        refWrapper.eq(PositionAssessorRoleConfig::getRoleCode, roleCode);
        long refCount = assessorRoleConfigMapper.selectCount(refWrapper);
        if (refCount > 0) {
            throw new BusinessException(400,
                    "角色 " + roleCode + " 被 " + refCount + " 个岗位配置引用，无法删除");
        }

        LambdaQueryWrapper<ProjectRoleAssignment> assignWrapper = new LambdaQueryWrapper<>();
        assignWrapper.eq(ProjectRoleAssignment::getProjectRoleCode, roleCode);
        long assignCount = roleAssignmentMapper.selectCount(assignWrapper);
        if (assignCount > 0) {
            throw new BusinessException(400,
                    "角色 " + roleCode + " 被 " + assignCount + " 条项目角色分配记录引用，无法删除。请先解除所有分配。");
        }

        LambdaQueryWrapper<ProjectKpiConfig> kpiWrapper = new LambdaQueryWrapper<>();
        kpiWrapper.eq(ProjectKpiConfig::getProjectRoleCode, roleCode);
        long kpiCount = projectKpiMapper.selectCount(kpiWrapper);
        if (kpiCount > 0) {
            throw new BusinessException(400,
                    "角色 " + roleCode + " 被 " + kpiCount + " 条项目KPI配置引用，无法删除。请先删除相关KPI配置。");
        }

        baseMapper.deleteById(roleCode);
    }

    // 功能：切换角色启用/停用状态——仅更新 is_active 字段
    @Transactional
    public ProjectRoleDTO toggleProjectRole(String roleCode) {
        ProjectRole existing = baseMapper.selectById(roleCode);
        if (existing == null) {
            throw new BusinessException(404, "角色不存在: " + roleCode);
        }
        existing.setIsActive(!existing.getIsActive());
        existing.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(existing);
        return toDTO(existing);
    }

    // 功能：将 ProjectRole 实体转为 DTO，过滤 deleted 和 version 内部字段
    private ProjectRoleDTO toDTO(ProjectRole role) {
        ProjectRoleDTO dto = new ProjectRoleDTO();
        dto.setRoleCode(role.getRoleCode());
        dto.setRoleName(role.getRoleName());
        dto.setDescription(role.getDescription());
        dto.setIsActive(role.getIsActive());
        dto.setCreatedAt(role.getCreatedAt());
        dto.setUpdatedAt(role.getUpdatedAt());
        return dto;
    }
}
