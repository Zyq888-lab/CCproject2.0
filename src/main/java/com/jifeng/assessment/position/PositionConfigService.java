// 模块用途：岗位考核配置业务逻辑——CRUD、权重校验、考核人角色关联管理
// 依赖文件：PositionConfigMapper.java, PositionAssessorRoleMapper.java, ProjectRoleMapper.java, BaseService.java
// 修改注意：权重校验用 BigDecimal + 容差 0.001，角色引用校验在关联考核人角色时执行
package com.jifeng.assessment.position;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PositionConfigService extends BaseService<PositionConfigMapper, PositionAssessmentConfig> {

    private final PositionAssessorRoleMapper assessorRoleMapper;
    private final ProjectRoleMapper projectRoleMapper;

    private static final BigDecimal TOLERANCE = new BigDecimal("0.001");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final Set<String> VALID_FUNC_ASSESS_MODES = Set.of("DIRECT_LEADER", "ORG_LEADER");

    // 功能：获取所有不重复的岗位分类——从 position_assessment_config 表 DISTINCT 查询
    public List<String> getDistinctCategories() {
        return baseMapper.selectList(null).stream()
                .map(PositionAssessmentConfig::getCategory)
                .distinct()
                .sorted()
                .toList();
    }

    // 功能：分页查询岗位配置列表，支持按岗位分类和岗位名称筛选，附带考核人角色名称
    public PageResult<PositionAssessmentConfig> listConfigs(int pageNum, int pageSize, String category, String position) {
        LambdaQueryWrapper<PositionAssessmentConfig> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            wrapper.eq(PositionAssessmentConfig::getCategory, category);
        }
        if (StringUtils.hasText(position)) {
            wrapper.like(PositionAssessmentConfig::getPosition, position);
        }
        wrapper.orderByAsc(PositionAssessmentConfig::getId);
        Page<PositionAssessmentConfig> page = new Page<>(pageNum, pageSize);
        Page<PositionAssessmentConfig> result = baseMapper.selectPage(page, wrapper);

        List<PositionAssessmentConfig> records = result.getRecords();
        if (!records.isEmpty()) {
            List<Long> configIds = records.stream().map(PositionAssessmentConfig::getId).toList();

            // 批量查询考核人角色关联
            LambdaQueryWrapper<PositionAssessorRoleConfig> arWrapper = new LambdaQueryWrapper<>();
            arWrapper.in(PositionAssessorRoleConfig::getPositionConfigId, configIds);
            List<PositionAssessorRoleConfig> arList = assessorRoleMapper.selectList(arWrapper);

            if (!arList.isEmpty()) {
                // 收集所有 roleCode，批量查 project_role 拿到 roleName
                Set<String> roleCodes = arList.stream().map(PositionAssessorRoleConfig::getRoleCode).collect(Collectors.toSet());
                LambdaQueryWrapper<ProjectRole> prWrapper = new LambdaQueryWrapper<>();
                prWrapper.in(ProjectRole::getRoleCode, roleCodes);
                Map<String, String> roleNameMap = projectRoleMapper.selectList(prWrapper).stream()
                        .collect(Collectors.toMap(ProjectRole::getRoleCode, ProjectRole::getRoleName, (a, b) -> a));

                // 构建 configId → roleNames 映射
                Map<Long, List<String>> configRoleNamesMap = arList.stream().collect(Collectors.groupingBy(
                        PositionAssessorRoleConfig::getPositionConfigId,
                        Collectors.mapping(ar -> roleNameMap.getOrDefault(ar.getRoleCode(), ar.getRoleCode()), Collectors.toList())
                ));

                // 回填到每个实体
                records.forEach(r -> r.setAssessorRoleNames(configRoleNamesMap.getOrDefault(r.getId(), List.of())));
            } else {
                records.forEach(r -> r.setAssessorRoleNames(List.of()));
            }
        }

        return PageResult.of(result.getTotal(), (int) result.getCurrent(), (int) result.getSize(), records);
    }

    // 功能：查询单个岗位配置
    public PositionAssessmentConfig getConfig(Long id) {
        PositionAssessmentConfig config = baseMapper.selectById(id);
        if (config == null) {
            throw new BusinessException(404, "岗位配置不存在: " + id);
        }
        return config;
    }

    // 功能：创建岗位配置——校验权重之和为100%、必填字段
    @Transactional
    public PositionAssessmentConfig createConfig(PositionAssessmentConfig config) {
        validateWeights(config);
        validateFuncAssessMode(config.getFuncAssessMode());
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(config);
        return config;
    }

    // 功能：更新岗位配置——乐观锁防并发覆盖，校验权重
    @Transactional
    public PositionAssessmentConfig updateConfig(Long id, PositionAssessmentConfig request) {
        PositionAssessmentConfig existing = baseMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "岗位配置不存在: " + id);
        }

        if (StringUtils.hasText(request.getCategory())) {
            existing.setCategory(request.getCategory());
        }
        if (StringUtils.hasText(request.getPosition())) {
            existing.setPosition(request.getPosition());
        }
        if (request.getIsProjectBased() != null) {
            existing.setIsProjectBased(request.getIsProjectBased());
        }
        if (request.getDefaultProjectRole() != null) {
            existing.setDefaultProjectRole(request.getDefaultProjectRole());
        }
        if (request.getFuncAssessMode() != null) {
            validateFuncAssessMode(request.getFuncAssessMode());
            existing.setFuncAssessMode(request.getFuncAssessMode());
        }
        if (request.getProjectWeight() != null) {
            existing.setProjectWeight(request.getProjectWeight());
        }
        if (request.getFuncWeight() != null) {
            existing.setFuncWeight(request.getFuncWeight());
        }

        validateWeights(existing);
        existing.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(existing);
        return baseMapper.selectById(id);
    }

    // 功能：删除岗位配置——逻辑删除
    @Transactional
    public void deleteConfig(Long id) {
        PositionAssessmentConfig existing = baseMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "岗位配置不存在: " + id);
        }
        baseMapper.deleteById(id);
    }

    // 功能：查询岗位配置关联的考核人角色列表（含角色名称）
    public List<PositionAssessorRoleConfig> listAssessorRoles(Long configId) {
        LambdaQueryWrapper<PositionAssessorRoleConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PositionAssessorRoleConfig::getPositionConfigId, configId)
                .orderByAsc(PositionAssessorRoleConfig::getId);
        List<PositionAssessorRoleConfig> list = assessorRoleMapper.selectList(wrapper);
        if (!list.isEmpty()) {
            Set<String> roleCodes = list.stream().map(PositionAssessorRoleConfig::getRoleCode).collect(Collectors.toSet());
            Map<String, String> roleNameMap = projectRoleMapper.selectList(
                    new LambdaQueryWrapper<ProjectRole>().in(ProjectRole::getRoleCode, roleCodes)
            ).stream().collect(Collectors.toMap(ProjectRole::getRoleCode, ProjectRole::getRoleName, (a, b) -> a));
            list.forEach(ar -> ar.setRoleName(roleNameMap.getOrDefault(ar.getRoleCode(), ar.getRoleCode())));
        }
        return list;
    }

    // 功能：新增考核人角色关联——校验角色存在(D1)、未重复关联
    @Transactional
    public PositionAssessorRoleConfig addAssessorRole(Long configId, String roleCode) {
        if (baseMapper.selectById(configId) == null) {
            throw new BusinessException(404, "岗位配置不存在: " + configId);
        }
        ProjectRole role = projectRoleMapper.selectById(roleCode);
        if (role == null) {
            throw new BusinessException(400, "角色" + roleCode + "在项目角色表中不存在");
        }

        // 检查重复关联
        LambdaQueryWrapper<PositionAssessorRoleConfig> dupWrapper = new LambdaQueryWrapper<>();
        dupWrapper.eq(PositionAssessorRoleConfig::getPositionConfigId, configId)
                .eq(PositionAssessorRoleConfig::getRoleCode, roleCode);
        if (assessorRoleMapper.selectCount(dupWrapper) > 0) {
            throw new BusinessException(409, "该岗位配置已关联角色" + roleCode);
        }

        PositionAssessorRoleConfig assoc = new PositionAssessorRoleConfig();
        assoc.setPositionConfigId(configId);
        assoc.setRoleCode(roleCode);
        assoc.setCreatedAt(LocalDateTime.now());
        assoc.setUpdatedAt(LocalDateTime.now());
        try {
            assessorRoleMapper.insert(assoc);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "该岗位配置已关联角色" + roleCode);
        }
        return assoc;
    }

    // 功能：移除考核人角色关联——逻辑删除
    @Transactional
    public void removeAssessorRole(Long configId, Long assessorRoleId) {
        PositionAssessorRoleConfig assoc = assessorRoleMapper.selectById(assessorRoleId);
        if (assoc == null || !assoc.getPositionConfigId().equals(configId)) {
            throw new BusinessException(404, "考核人角色关联不存在");
        }
        assessorRoleMapper.deleteById(assessorRoleId);
    }

    // 功能：职能考核方式校验——仅允许 DIRECT_LEADER / ORG_LEADER
    private void validateFuncAssessMode(String mode) {
        if (mode != null && !VALID_FUNC_ASSESS_MODES.contains(mode)) {
            throw new BusinessException(400,
                    "无效的职能考核方式: " + mode + "，有效值: " + VALID_FUNC_ASSESS_MODES);
        }
    }

    // 功能：权重校验——项目权重+职能权重之和必须为1.0（容差±0.001，应对BigDecimal浮点精度问题）
    private void validateWeights(PositionAssessmentConfig config) {
        if (config.getProjectWeight() == null || config.getFuncWeight() == null) {
            throw new BusinessException(400, "项目权重和职能权重不能为空");
        }
        BigDecimal sum = config.getProjectWeight().add(config.getFuncWeight());
        if (sum.subtract(ONE).abs().compareTo(TOLERANCE) > 0) {
            throw new BusinessException(400,
                    "项目权重与职能权重之和必须为100%（当前: " + sum + "）");
        }
    }
}
