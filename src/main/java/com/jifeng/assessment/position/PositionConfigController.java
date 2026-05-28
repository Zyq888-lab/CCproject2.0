// 模块用途：岗位考核配置REST接口——提供配置CRUD和考核人角色关联API
// 依赖文件：PositionConfigService.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/position-configs 开头
package com.jifeng.assessment.position;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class PositionConfigController extends BaseController {

    private final PositionConfigService positionConfigService;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // 功能：获取所有不重复的岗位分类列表——供前端下拉框动态获取
    @GetMapping("/api/v1/position-configs/categories")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<List<String>> getCategories() {
        return ok(positionConfigService.getDistinctCategories());
    }

    // 功能：分页查询岗位配置，支持按岗位分类和岗位名称筛选
    @GetMapping("/api/v1/position-configs")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<PageResult<PositionAssessmentConfig>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String position) {
        return ok(positionConfigService.listConfigs(page, size, category, position));
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
}
