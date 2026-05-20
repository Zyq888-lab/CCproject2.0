// 模块用途：KPI指标配置业务逻辑——项目KPI和职能KPI的CRUD、权重校验、启停切换
// 依赖文件：ProjectKpiMapper.java, FuncKpiMapper.java, WeightValidator.java, BaseService.java
// 修改注意：权重校验复用 WeightValidator，同一scope下所有权重之和不能超过100%
package com.jifeng.assessment.kpi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KpiConfigService {

    private final ProjectKpiMapper projectKpiMapper;
    private final FuncKpiMapper funcKpiMapper;

    // ========================================
    // 项目KPI
    // ========================================

    // 功能：查询项目KPI列表——支持按角色、阶段、启用状态筛选
    public List<ProjectKpiConfig> listProjectKpis(String roleCode, String stage, Boolean isActive) {
        LambdaQueryWrapper<ProjectKpiConfig> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(roleCode)) {
            wrapper.eq(ProjectKpiConfig::getProjectRoleCode, roleCode);
        }
        if (StringUtils.hasText(stage)) {
            wrapper.eq(ProjectKpiConfig::getProjectStage, stage);
        }
        if (isActive != null) {
            wrapper.eq(ProjectKpiConfig::getIsActive, isActive);
        }
        wrapper.orderByAsc(ProjectKpiConfig::getSortOrder, ProjectKpiConfig::getId);
        return projectKpiMapper.selectList(wrapper);
    }

    // 功能：新增项目KPI——校验同角色同阶段下权重之和不超过100%、指标名不重复
    @Transactional
    public ProjectKpiConfig createProjectKpi(ProjectKpiConfig config) {
        if (!StringUtils.hasText(config.getProjectRoleCode()) || !StringUtils.hasText(config.getProjectStage())) {
            throw new BusinessException(400, "项目角色和项目阶段不能为空");
        }
        if (!StringUtils.hasText(config.getKpiName())) {
            throw new BusinessException(400, "指标名称不能为空");
        }
        if (config.getWeight() == null) {
            throw new BusinessException(400, "指标权重不能为空");
        }

        // 重复指标名检查
        checkDuplicateKpiName(config.getProjectRoleCode(), config.getProjectStage(), config.getKpiName());

        // 权重校验：同 scope 下已有权重 + 新权重 ≤ 1.0
        BigDecimal existingSum = sumWeightsByScope(config.getProjectRoleCode(), config.getProjectStage());
        WeightValidator.validateNotExceed(config.getWeight(), existingSum, "项目KPI");

        config.setIsActive(config.getIsActive() != null ? config.getIsActive() : true);
        config.setSortOrder(config.getSortOrder() != null ? config.getSortOrder() : 0);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        projectKpiMapper.insert(config);
        return config;
    }

    // 功能：更新项目KPI——乐观锁防并发覆盖，校验权重
    @Transactional
    public ProjectKpiConfig updateProjectKpi(Long id, ProjectKpiConfig request) {
        ProjectKpiConfig existing = projectKpiMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "项目KPI配置不存在: " + id);
        }

        if (StringUtils.hasText(request.getKpiName())) {
            existing.setKpiName(request.getKpiName());
        }
        if (StringUtils.hasText(request.getEvaluationCriteria())) {
            existing.setEvaluationCriteria(request.getEvaluationCriteria());
        }
        if (request.getWeight() != null) {
            // 校验：同 scope 下其他KPI权重 + 新权重 ≤ 1.0
            BigDecimal scopeSum = sumWeightsByScope(existing.getProjectRoleCode(), existing.getProjectStage());
            BigDecimal otherSum = scopeSum.subtract(existing.getWeight());
            WeightValidator.validateNotExceed(request.getWeight(), otherSum, "项目KPI");
            existing.setWeight(request.getWeight());
        }
        if (request.getSortOrder() != null) {
            existing.setSortOrder(request.getSortOrder());
        }
        if (request.getVersion() != null) {
            existing.setVersion(request.getVersion());
        }

        existing.setUpdatedAt(LocalDateTime.now());
        if (projectKpiMapper.updateById(existing) == 0) {
            throw new BusinessException(409, "该项目KPI已被他人修改，请刷新后重试");
        }
        return projectKpiMapper.selectById(id);
    }

    // 功能：切换项目KPI启用/停用状态
    @Transactional
    public ProjectKpiConfig toggleProjectKpi(Long id) {
        ProjectKpiConfig existing = projectKpiMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "项目KPI配置不存在: " + id);
        }
        existing.setIsActive(!existing.getIsActive());
        existing.setUpdatedAt(LocalDateTime.now());
        if (projectKpiMapper.updateById(existing) == 0) {
            throw new BusinessException(409, "该项目KPI已被他人修改，请刷新后重试");
        }
        return projectKpiMapper.selectById(id);
    }

    // 功能：删除项目KPI——逻辑删除
    @Transactional
    public void deleteProjectKpi(Long id) {
        ProjectKpiConfig existing = projectKpiMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "项目KPI配置不存在: " + id);
        }
        projectKpiMapper.deleteById(id);
    }

    // ========================================
    // 职能KPI
    // ========================================

    // 功能：查询职能KPI列表——支持按岗位分类、岗位名称筛选
    public List<FuncKpiConfig> listFuncKpis(String category, String position) {
        LambdaQueryWrapper<FuncKpiConfig> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            wrapper.eq(FuncKpiConfig::getCategory, category);
        }
        if (StringUtils.hasText(position)) {
            wrapper.eq(FuncKpiConfig::getPosition, position);
        }
        wrapper.orderByAsc(FuncKpiConfig::getSortOrder, FuncKpiConfig::getId);
        return funcKpiMapper.selectList(wrapper);
    }

    // 功能：新增职能KPI——校验同分类同岗位下权重之和不超过100%、指标名不重复
    @Transactional
    public FuncKpiConfig createFuncKpi(FuncKpiConfig config) {
        if (!StringUtils.hasText(config.getCategory()) || !StringUtils.hasText(config.getPosition())) {
            throw new BusinessException(400, "岗位分类和岗位名称不能为空");
        }
        if (!StringUtils.hasText(config.getKpiName())) {
            throw new BusinessException(400, "指标名称不能为空");
        }
        if (config.getWeight() == null) {
            throw new BusinessException(400, "指标权重不能为空");
        }

        // 重复指标名检查
        checkDuplicateFuncKpiName(config.getCategory(), config.getPosition(), config.getKpiName());

        // 权重校验
        BigDecimal existingSum = sumFuncWeightsByScope(config.getCategory(), config.getPosition());
        WeightValidator.validateNotExceed(config.getWeight(), existingSum, "职能KPI");

        config.setIsActive(config.getIsActive() != null ? config.getIsActive() : true);
        config.setSortOrder(config.getSortOrder() != null ? config.getSortOrder() : 0);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        funcKpiMapper.insert(config);
        return config;
    }

    // 功能：更新职能KPI——乐观锁防并发覆盖，校验权重
    @Transactional
    public FuncKpiConfig updateFuncKpi(Long id, FuncKpiConfig request) {
        FuncKpiConfig existing = funcKpiMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "职能KPI配置不存在: " + id);
        }

        if (StringUtils.hasText(request.getKpiName())) {
            existing.setKpiName(request.getKpiName());
        }
        if (StringUtils.hasText(request.getEvaluationCriteria())) {
            existing.setEvaluationCriteria(request.getEvaluationCriteria());
        }
        if (request.getWeight() != null) {
            BigDecimal scopeSum = sumFuncWeightsByScope(existing.getCategory(), existing.getPosition());
            BigDecimal otherSum = scopeSum.subtract(existing.getWeight());
            WeightValidator.validateNotExceed(request.getWeight(), otherSum, "职能KPI");
            existing.setWeight(request.getWeight());
        }
        if (request.getSortOrder() != null) {
            existing.setSortOrder(request.getSortOrder());
        }
        if (request.getVersion() != null) {
            existing.setVersion(request.getVersion());
        }

        existing.setUpdatedAt(LocalDateTime.now());
        if (funcKpiMapper.updateById(existing) == 0) {
            throw new BusinessException(409, "该职能KPI已被他人修改，请刷新后重试");
        }
        return funcKpiMapper.selectById(id);
    }

    // 功能：切换职能KPI启用/停用状态
    @Transactional
    public FuncKpiConfig toggleFuncKpi(Long id) {
        FuncKpiConfig existing = funcKpiMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "职能KPI配置不存在: " + id);
        }
        existing.setIsActive(!existing.getIsActive());
        existing.setUpdatedAt(LocalDateTime.now());
        if (funcKpiMapper.updateById(existing) == 0) {
            throw new BusinessException(409, "该职能KPI已被他人修改，请刷新后重试");
        }
        return funcKpiMapper.selectById(id);
    }

    // 功能：删除职能KPI——逻辑删除
    @Transactional
    public void deleteFuncKpi(Long id) {
        FuncKpiConfig existing = funcKpiMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "职能KPI配置不存在: " + id);
        }
        funcKpiMapper.deleteById(id);
    }

    // ========================================
    // 私有辅助方法
    // ========================================

    // 功能：计算同一角色+阶段下所有KPI权重之和
    private BigDecimal sumWeightsByScope(String roleCode, String stage) {
        LambdaQueryWrapper<ProjectKpiConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectKpiConfig::getProjectRoleCode, roleCode)
                .eq(ProjectKpiConfig::getProjectStage, stage);
        List<ProjectKpiConfig> list = projectKpiMapper.selectList(wrapper);
        return WeightValidator.sum(list.stream().map(ProjectKpiConfig::getWeight).collect(Collectors.toList()));
    }

    // 功能：计算同一分类+岗位下所有职能KPI权重之和
    private BigDecimal sumFuncWeightsByScope(String category, String position) {
        LambdaQueryWrapper<FuncKpiConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuncKpiConfig::getCategory, category)
                .eq(FuncKpiConfig::getPosition, position);
        List<FuncKpiConfig> list = funcKpiMapper.selectList(wrapper);
        return WeightValidator.sum(list.stream().map(FuncKpiConfig::getWeight).collect(Collectors.toList()));
    }

    // 功能：检查同一 scope 下指标名是否重复
    private void checkDuplicateKpiName(String roleCode, String stage, String kpiName) {
        LambdaQueryWrapper<ProjectKpiConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectKpiConfig::getProjectRoleCode, roleCode)
                .eq(ProjectKpiConfig::getProjectStage, stage)
                .eq(ProjectKpiConfig::getKpiName, kpiName);
        if (projectKpiMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(409, "该角色+阶段下已存在指标: " + kpiName);
        }
    }

    // 功能：检查同一 scope 下职能指标名是否重复
    private void checkDuplicateFuncKpiName(String category, String position, String kpiName) {
        LambdaQueryWrapper<FuncKpiConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuncKpiConfig::getCategory, category)
                .eq(FuncKpiConfig::getPosition, position)
                .eq(FuncKpiConfig::getKpiName, kpiName);
        if (funcKpiMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(409, "该岗位分类+岗位下已存在指标: " + kpiName);
        }
    }
}
