// 模块用途：岗位考核配置REST接口——提供配置CRUD和考核人角色关联API
// 依赖文件：PositionConfigService.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/position-configs 开头
package com.jifeng.assessment.position;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.positioncategory.PositionCategory;
import com.jifeng.assessment.positioncategory.PositionCategoryMapper;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class PositionConfigController extends BaseController {

    private final PositionConfigService positionConfigService;
    private final PositionCategoryMapper positionCategoryMapper;
    private final ProjectRoleMapper projectRoleMapper;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // 功能：获取所有不重复的岗位分类列表——供前端下拉框动态获取
    @GetMapping("/api/v1/position-configs/categories")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<List<String>> getCategories() {
        return ok(positionConfigService.getDistinctCategories());
    }

    // 功能：分页查询岗位配置，支持按岗位分类、岗位名称、默认角色筛选
    @GetMapping("/api/v1/position-configs")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<PageResult<PositionAssessmentConfig>> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "position", required = false) String position,
            @RequestParam(name = "defaultProjectRole", required = false) String defaultProjectRole) {
        return ok(positionConfigService.listConfigs(page, size, category, position, defaultProjectRole));
    }

    // 功能：创建岗位配置——校验权重之和为100%
    @PostMapping("/api/v1/position-configs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PositionAssessmentConfig> create(
            @Valid @RequestBody CreateRequest request) {
        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory(request.getCategory());
        config.setPosition(request.getPosition());
        config.setIsProjectBased(request.getIsProjectBased());
        config.setDefaultProjectRole(request.getDefaultProjectRole());
        config.setFuncAssessMode(request.getFuncAssessMode());
        config.setProjectWeight(request.getProjectWeight().divide(HUNDRED));
        config.setFuncWeight(request.getFuncWeight().divide(HUNDRED));
        return ok(positionConfigService.createConfig(config));
    }

    // 功能：更新岗位配置——乐观锁防并发覆盖
    @PutMapping("/api/v1/position-configs/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PositionAssessmentConfig> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRequest request) {
        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory(request.getCategory());
        config.setPosition(request.getPosition());
        config.setIsProjectBased(request.getIsProjectBased());
        config.setDefaultProjectRole(request.getDefaultProjectRole());
        config.setFuncAssessMode(request.getFuncAssessMode());
        config.setProjectWeight(request.getProjectWeight().divide(HUNDRED));
        config.setFuncWeight(request.getFuncWeight().divide(HUNDRED));
        return ok(positionConfigService.updateConfig(id, config));
    }

    // 功能：逻辑删除岗位配置
    @DeleteMapping("/api/v1/position-configs/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        positionConfigService.deleteConfig(id);
        return ok("已删除", null);
    }

    // 功能：查询岗位配置关联的考核人角色列表
    @GetMapping("/api/v1/position-configs/{configId}/assessor-roles")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<List<PositionAssessorRoleConfig>> listAssessorRoles(
            @PathVariable Long configId) {
        return ok(positionConfigService.listAssessorRoles(configId));
    }

    // 功能：新增考核人角色关联——校验角色在 project_role 表中存在(D1)
    @PostMapping("/api/v1/position-configs/{configId}/assessor-roles")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<PositionAssessorRoleConfig> addAssessorRole(
            @PathVariable Long configId,
            @Valid @RequestBody AssessorRoleRequest request) {
        return ok(positionConfigService.addAssessorRole(configId, request.getRoleCode()));
    }

    // 功能：移除考核人角色关联
    @DeleteMapping("/api/v1/position-configs/{configId}/assessor-roles/{assessorRoleId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<Void> removeAssessorRole(
            @PathVariable Long configId,
            @PathVariable Long assessorRoleId) {
        positionConfigService.removeAssessorRole(configId, assessorRoleId);
        return ok("已移除", null);
    }

    @Data
    public static class CreateRequest {
        @NotBlank
        private String category;
        @NotBlank
        private String position;
        @NotNull
        private Boolean isProjectBased;
        private String defaultProjectRole;
        private String funcAssessMode;
        @NotNull
        private BigDecimal projectWeight;
        @NotNull
        private BigDecimal funcWeight;
    }

    @Data
    public static class UpdateRequest {
        private String category;
        private String position;
        private Boolean isProjectBased;
        private String defaultProjectRole;
        private String funcAssessMode;
        private BigDecimal projectWeight;
        private BigDecimal funcWeight;
    }

    @Data
    public static class AssessorRoleRequest {
        @NotBlank
        private String roleCode;
    }

    // 批量导入岗位配置——逐行校验分类、角色、权重
    @PostMapping("/api/v1/position-configs/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> importConfigs(@RequestBody List<ImportConfigRequest> requests) {
        // 批量预取有效分类和角色，避免逐行查询
        Set<String> validCategories = positionCategoryMapper.selectList(
                new LambdaQueryWrapper<PositionCategory>().eq(PositionCategory::getDeleted, 0))
                .stream().map(PositionCategory::getName).collect(Collectors.toSet());
        Set<String> validRoleCodes = projectRoleMapper.selectList(
                new LambdaQueryWrapper<ProjectRole>().eq(ProjectRole::getIsActive, true))
                .stream().map(ProjectRole::getRoleCode).collect(Collectors.toSet());

        int success = 0; List<String> errors = new ArrayList<>();
        int rowNum = 0;
        for (ImportConfigRequest req : requests) {
            rowNum++;
            String label = "第" + rowNum + "行(" + req.getCategory() + "/" + req.getPosition() + ")";
            try {
                // 必填校验
                if (!StringUtils.hasText(req.getCategory())) {
                    errors.add(label + ": 岗位分类不能为空"); continue;
                }
                if (!StringUtils.hasText(req.getPosition())) {
                    errors.add(label + ": 岗位名称不能为空"); continue;
                }
                // 分类校验
                if (!validCategories.contains(req.getCategory())) {
                    errors.add(label + ": 岗位分类'" + req.getCategory() + "'不存在，请先在岗位分类管理中维护");
                    continue;
                }
                // 角色校验
                if (StringUtils.hasText(req.getDefaultProjectRole())
                        && !validRoleCodes.contains(req.getDefaultProjectRole())) {
                    errors.add(label + ": 默认项目角色编码'" + req.getDefaultProjectRole() + "'在项目角色表中不存在");
                    continue;
                }
                // 职能考核模式校验
                if (StringUtils.hasText(req.getFuncAssessMode())) {
                    Set<String> validModes = Set.of("DIRECT_LEADER", "ORG_LEADER");
                    if (!validModes.contains(req.getFuncAssessMode())) {
                        errors.add(label + ": 无效的职能考核方式'" + req.getFuncAssessMode() + "'，有效值: DIRECT_LEADER, ORG_LEADER");
                        continue;
                    }
                }

                PositionAssessmentConfig c = new PositionAssessmentConfig();
                c.setCategory(req.getCategory());
                c.setPosition(req.getPosition());
                c.setIsProjectBased(req.getIsProjectBased() != null ? req.getIsProjectBased() : true);
                c.setDefaultProjectRole(req.getDefaultProjectRole());
                c.setFuncAssessMode(req.getFuncAssessMode());
                // 权重：前端传整数百分比(70=70%)，后端统一除以100转为小数存储(0.70)
                c.setProjectWeight(req.getProjectWeight() != null
                        ? req.getProjectWeight().divide(HUNDRED) : new BigDecimal("0.70"));
                c.setFuncWeight(req.getFuncWeight() != null
                        ? req.getFuncWeight().divide(HUNDRED) : new BigDecimal("0.30"));
                positionConfigService.createConfig(c);
                success++;
            } catch (BusinessException e) {
                errors.add(label + ": " + e.getMessage());
            } catch (Exception e) {
                errors.add(label + ": 系统错误——" + e.getMessage());
            }
        }
        return ok(Map.of("success", success, "errors", errors));
    }

    @Data
    public static class ImportConfigRequest {
        private String category; private String position; private Boolean isProjectBased;
        private String defaultProjectRole; private String funcAssessMode;
        private BigDecimal projectWeight; private BigDecimal funcWeight;
    }
}
