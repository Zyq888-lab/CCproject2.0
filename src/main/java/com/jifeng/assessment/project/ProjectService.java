// 模块用途：项目管理业务逻辑——CRUD、阶段确认（乐观锁）、阶段重置（管理员）
// 依赖文件：ProjectMapper.java, Project.java, ProjectDTO.java, ProjectStage.java, BaseService.java
// 修改注意：confirmStage和resetStage使用乐观锁，冲突时BaseService自动抛出409
package com.jifeng.assessment.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService extends BaseService<ProjectMapper, Project> {

    // 功能：分页查询项目列表，支持按 projectStage 和 status 筛选
    public PageResult<ProjectDTO> listProjects(PageQuery query, String stage, String status) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(stage)) {
            wrapper.eq(Project::getProjectStage, stage);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Project::getStatus, status);
        }
        wrapper.orderByAsc(Project::getProjectCode);
        PageResult<Project> page = selectPage(query, wrapper);
        List<ProjectDTO> dtoList = page.getList().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(page.getTotal(), page.getPage(), page.getSize(), dtoList);
    }

    // 功能：创建项目——校验projectCode非空不重复、projectStage为有效枚举值、默认status=ACTIVE
    @Transactional
    public ProjectDTO createProject(Project project) {
        if (!StringUtils.hasText(project.getProjectCode())) {
            throw new BusinessException(400, "项目编码不能为空");
        }
        if (!StringUtils.hasText(project.getProjectName())) {
            throw new BusinessException(400, "项目名称不能为空");
        }
        if (!StringUtils.hasText(project.getProjectStage())) {
            throw new BusinessException(400, "项目阶段不能为空");
        }
        if (ProjectStage.fromCode(project.getProjectStage()) == null) {
            throw new BusinessException(400,
                    "无效的项目阶段: " + project.getProjectStage() + "，有效值: P2, P3, P4, P5");
        }
        if (baseMapper.selectById(project.getProjectCode()) != null) {
            throw new BusinessException(409, "项目编码" + project.getProjectCode() + "已存在");
        }
        project.setStatus(StringUtils.hasText(project.getStatus()) ? project.getStatus() : "ACTIVE");
        project.setStageConfirmed(false);
        try {
            baseMapper.insert(project);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "项目编码" + project.getProjectCode() + "已存在");
        }
        return toDTO(project);
    }

    // 功能：PM确认项目阶段——使用乐观锁，记录确认人和确认时间，已确认则拒绝重复操作
    @Transactional
    public ProjectDTO confirmStage(String projectCode) {
        Project existing = baseMapper.selectById(projectCode);
        if (existing == null) {
            throw new BusinessException(404, "项目不存在: " + projectCode);
        }
        if (Boolean.TRUE.equals(existing.getStageConfirmed())) {
            throw new BusinessException(400, "项目阶段已确认，无需重复确认");
        }
        String currentUser = getCurrentUsername();
        existing.setStageConfirmed(true);
        existing.setConfirmedBy(currentUser);
        existing.setConfirmedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(existing);
        return toDTO(baseMapper.selectById(projectCode));
    }

    // 功能：ADMIN强制重置阶段确认——使用乐观锁，清空确认人、确认时间
    @Transactional
    public ProjectDTO resetStage(String projectCode) {
        Project existing = baseMapper.selectById(projectCode);
        if (existing == null) {
            throw new BusinessException(404, "项目不存在: " + projectCode);
        }
        existing.setStageConfirmed(false);
        existing.setConfirmedBy(null);
        existing.setConfirmedAt(null);
        existing.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(existing);
        return toDTO(baseMapper.selectById(projectCode));
    }

    // 功能：从Spring Security上下文获取当前登录用户名，未认证时返回"system"
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "system";
        }
        return auth.getName();
    }

    // 功能：将 Project 实体转为 DTO，过滤 deleted 和 version 内部字段
    private ProjectDTO toDTO(Project project) {
        ProjectDTO dto = new ProjectDTO();
        dto.setProjectCode(project.getProjectCode());
        dto.setProjectName(project.getProjectName());
        dto.setProjectStage(project.getProjectStage());
        dto.setDescription(project.getDescription());
        dto.setStatus(project.getStatus());
        dto.setStageConfirmed(project.getStageConfirmed());
        dto.setConfirmedBy(project.getConfirmedBy());
        dto.setConfirmedAt(project.getConfirmedAt());
        dto.setCreatedAt(project.getCreatedAt());
        dto.setUpdatedAt(project.getUpdatedAt());
        return dto;
    }
}
