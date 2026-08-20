// 模块用途：KPI指标配置REST接口——项目KPI和职能KPI的CRUD及启停切换
// 依赖文件：KpiConfigService.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/kpi-configs 开头
package com.jifeng.assessment.kpi;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class KpiConfigController extends BaseController {

    private final KpiConfigService kpiConfigService;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // ========================================
    // 项目KPI
    // ========================================

    // 功能：查询项目KPI列表——支持按角色、阶段、启用状态筛选
    @GetMapping("/api/v1/kpi-configs/project")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<List<ProjectKpiConfig>> listProjectKpis(
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Boolean isActive) {
        return ok(kpiConfigService.listProjectKpis(roleCode, stage, isActive));
    }

    // 功能：新增项目KPI——校验同角色同阶段下权重之和不超过100%
    @PostMapping("/api/v1/kpi-configs/project")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProjectKpiConfig> createProjectKpi(
            @Valid @RequestBody ProjectKpiRequest request) {
        ProjectKpiConfig config = new ProjectKpiConfig();
        config.setProjectRoleCode(request.getProjectRoleCode());
        config.setProjectStage(request.getProjectStage());
        config.setKpiName(request.getKpiName());
        config.setEvaluationCriteria(request.getEvaluationCriteria());
        config.setWeight(request.getWeight().divide(HUNDRED));
        config.setSortOrder(request.getSortOrder());
        return ok(kpiConfigService.createProjectKpi(config));
    }

    // 功能：更新项目KPI——乐观锁防并发覆盖
    @PutMapping("/api/v1/kpi-configs/project/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProjectKpiConfig> updateProjectKpi(
            @PathVariable Long id,
            @Valid @RequestBody ProjectKpiUpdateRequest request) {
        ProjectKpiConfig config = new ProjectKpiConfig();
        config.setKpiName(request.getKpiName());
        config.setEvaluationCriteria(request.getEvaluationCriteria());
        config.setWeight(request.getWeight());
        config.setSortOrder(request.getSortOrder());
        return ok(kpiConfigService.updateProjectKpi(id, config));
    }

    // 功能：切换项目KPI启用/停用
    @PutMapping("/api/v1/kpi-configs/project/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProjectKpiConfig> toggleProjectKpi(@PathVariable Long id) {
        return ok(kpiConfigService.toggleProjectKpi(id));
    }

    // 功能：删除项目KPI
    @DeleteMapping("/api/v1/kpi-configs/project/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteProjectKpi(@PathVariable Long id) {
        kpiConfigService.deleteProjectKpi(id);
        return ok("已删除", null);
    }

    // ========================================
    // 职能KPI
    // ========================================

    // 功能：查询职能KPI列表——支持按岗位分类、岗位名称筛选
    @GetMapping("/api/v1/kpi-configs/functional")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<List<FuncKpiConfig>> listFuncKpis(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String position) {
        return ok(kpiConfigService.listFuncKpis(category, position));
    }

    // 功能：新增职能KPI——校验同分类同岗位下权重之和不超过100%
    @PostMapping("/api/v1/kpi-configs/functional")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<FuncKpiConfig> createFuncKpi(
            @Valid @RequestBody FuncKpiRequest request) {
        FuncKpiConfig config = new FuncKpiConfig();
        config.setCategory(request.getCategory());
        config.setPosition(request.getPosition());
        config.setKpiName(request.getKpiName());
        config.setEvaluationCriteria(request.getEvaluationCriteria());
        config.setWeight(request.getWeight().divide(HUNDRED));
        config.setSortOrder(request.getSortOrder());
        return ok(kpiConfigService.createFuncKpi(config));
    }

    // 功能：更新职能KPI——乐观锁防并发覆盖
    @PutMapping("/api/v1/kpi-configs/functional/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<FuncKpiConfig> updateFuncKpi(
            @PathVariable Long id,
            @Valid @RequestBody FuncKpiUpdateRequest request) {
        FuncKpiConfig config = new FuncKpiConfig();
        config.setKpiName(request.getKpiName());
        config.setEvaluationCriteria(request.getEvaluationCriteria());
        config.setWeight(request.getWeight());
        config.setSortOrder(request.getSortOrder());
        return ok(kpiConfigService.updateFuncKpi(id, config));
    }

    // 功能：切换职能KPI启用/停用
    @PutMapping("/api/v1/kpi-configs/functional/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<FuncKpiConfig> toggleFuncKpi(@PathVariable Long id) {
        return ok(kpiConfigService.toggleFuncKpi(id));
    }

    // 功能：删除职能KPI
    @DeleteMapping("/api/v1/kpi-configs/functional/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteFuncKpi(@PathVariable Long id) {
        kpiConfigService.deleteFuncKpi(id);
        return ok("已删除", null);
    }

    // ========================================
    // 请求体
    // ========================================

    @Data
    public static class ProjectKpiRequest {
        @NotBlank
        private String projectRoleCode;
        @NotBlank
        private String projectStage;
        @NotBlank
        private String kpiName;
        private String evaluationCriteria;
        @NotNull
        private BigDecimal weight;
        private Integer sortOrder;
    }

    @Data
    public static class ProjectKpiUpdateRequest {
        private String kpiName;
        private String evaluationCriteria;
        private BigDecimal weight;
        private Integer sortOrder;
    }

    @Data
    public static class FuncKpiRequest {
        @NotBlank
        private String category;
        @NotBlank
        private String position;
        @NotBlank
        private String kpiName;
        private String evaluationCriteria;
        @NotNull
        private BigDecimal weight;
        private Integer sortOrder;
    }

    @Data
    public static class FuncKpiUpdateRequest {
        private String kpiName;
        private String evaluationCriteria;
        private BigDecimal weight;
        private Integer sortOrder;
    }

    // 批量导入项目KPI
    @PostMapping("/api/v1/kpi-configs/project-kpi/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> importProjectKpi(@RequestBody List<ProjectKpiImportRequest> requests) {
        int success = 0; List<String> errors = new ArrayList<>();
        for (ProjectKpiImportRequest req : requests) {
            try {
                ProjectKpiConfig c = new ProjectKpiConfig();
                c.setProjectRoleCode(req.getProjectRoleCode()); c.setProjectStage(req.getProjectStage());
                c.setKpiName(req.getKpiName()); c.setEvaluationCriteria(req.getEvaluationCriteria());
                c.setWeight(req.getWeight() != null ? req.getWeight() : java.math.BigDecimal.ZERO);
                c.setSortOrder(req.getSortOrder());
                kpiConfigService.createProjectKpi(c);
                success++;
            } catch (Exception e) { errors.add(req.getProjectRoleCode() + "/" + req.getProjectStage() + ": " + e.getMessage()); }
        }
        return ok(Map.of("success", success, "errors", errors));
    }

    @Data public static class ProjectKpiImportRequest {
        private String projectRoleCode; private String projectStage; private String kpiName;
        private String evaluationCriteria; private java.math.BigDecimal weight; private Integer sortOrder;
    }

    // 批量导入职能KPI
    @PostMapping("/api/v1/kpi-configs/func-kpi/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> importFuncKpi(@RequestBody List<FuncKpiImportRequest> requests) {
        int success = 0; List<String> errors = new ArrayList<>();
        for (FuncKpiImportRequest req : requests) {
            try {
                FuncKpiConfig fc = new FuncKpiConfig();
                fc.setCategory(req.getCategory()); fc.setPosition(req.getPosition());
                fc.setKpiName(req.getKpiName()); fc.setEvaluationCriteria(req.getEvaluationCriteria());
                fc.setWeight(req.getWeight() != null ? req.getWeight() : java.math.BigDecimal.ZERO);
                fc.setSortOrder(req.getSortOrder());
                kpiConfigService.createFuncKpi(fc);
                success++;
            } catch (Exception e) { errors.add(req.getCategory() + "/" + req.getPosition() + ": " + e.getMessage()); }
        }
        return ok(Map.of("success", success, "errors", errors));
    }

    @Data public static class FuncKpiImportRequest {
        private String category; private String position; private String kpiName;
        private String evaluationCriteria; private java.math.BigDecimal weight; private Integer sortOrder;
    }
}
