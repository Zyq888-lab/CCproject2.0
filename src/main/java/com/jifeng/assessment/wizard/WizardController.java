// 模块用途：配置向导REST接口——7步分步保存、进度查询、断点续配、重置
// 依赖文件：WizardService.java, 各业务模块Service, BaseController.java
// 修改注意：每步完成后自动推进currentStep，全部7步去重完成后标记completed=true
package com.jifeng.assessment.wizard;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.kpi.KpiConfigService;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigService;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectService;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleService;
import com.jifeng.assessment.roleassignment.RoleAssignmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
public class WizardController extends BaseController {

    private final WizardService wizardService;
    private final ProjectRoleService projectRoleService;
    private final ProjectService projectService;
    private final RoleAssignmentService roleAssignmentService;
    private final KpiConfigService kpiConfigService;
    private final PositionConfigService positionConfigService;
    private final PeriodService periodService;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private String currentUserId() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // 功能：查询当前用户的向导进度
    @GetMapping("/api/v1/wizard/progress")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<WizardProgressDTO> getProgress() {
        return ok(wizardService.getProgress(currentUserId()));
    }

    // 功能：步骤1——创建项目角色
    @PostMapping("/api/v1/wizard/step/project-role")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WizardStepResult> stepProjectRole(@Valid @RequestBody Step1Request request) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode(request.getRoleCode());
        role.setRoleName(request.getRoleName());
        role.setIsActive(true);
        projectRoleService.createProjectRole(role);
        return ok(advance(1));
    }

    // 功能：步骤2——标记导入完成（实际导入由 import 模块处理）
    @PostMapping("/api/v1/wizard/step/import-employee")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WizardStepResult> stepImportEmployee() {
        return ok(advance(2));
    }

    // 功能：步骤3——创建第一个项目
    @PostMapping("/api/v1/wizard/step/project")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WizardStepResult> stepProject(@Valid @RequestBody Step3Request request) {
        Project project = new Project();
        project.setProjectCode(request.getProjectCode());
        project.setProjectName(request.getProjectName());
        project.setProjectStage(request.getProjectStage());
        project.setDescription(request.getDescription());
        projectService.createProject(project);
        return ok(advance(3));
    }

    // 功能：步骤4——分配项目角色人员
    @PostMapping("/api/v1/wizard/step/role-assignment")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WizardStepResult> stepRoleAssignment(@Valid @RequestBody Step4Request request) {
        roleAssignmentService.assignEmployee(
                request.getProjectCode(), request.getRoleCode(), request.getEmployeeId());
        return ok(advance(4));
    }

    // 功能：步骤5——配置KPI指标
    @PostMapping("/api/v1/wizard/step/kpi")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WizardStepResult> stepKpi(@Valid @RequestBody Step5Request request) {
        ProjectKpiConfig projectKpi = new ProjectKpiConfig();
        projectKpi.setProjectRoleCode(request.getProjectRoleCode());
        projectKpi.setProjectStage(request.getProjectStage());
        projectKpi.setKpiName(request.getKpiName());
        projectKpi.setWeight(request.getWeight().divide(HUNDRED));
        kpiConfigService.createProjectKpi(projectKpi);
        return ok(advance(5));
    }

    // 功能：步骤6——配置岗位考核规则
    @PostMapping("/api/v1/wizard/step/position")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WizardStepResult> stepPosition(@Valid @RequestBody Step6Request request) {
        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory(request.getCategory());
        config.setPosition(request.getPosition());
        config.setIsProjectBased(request.getIsProjectBased());
        config.setProjectWeight(request.getProjectWeight().divide(HUNDRED));
        config.setFuncWeight(request.getFuncWeight().divide(HUNDRED));
        config.setFuncAssessMode(request.getFuncAssessMode());
        positionConfigService.createConfig(config);
        return ok(advance(6));
    }

    // 功能：步骤7——创建考核周期（向导最后一步）
    @PostMapping("/api/v1/wizard/step/period")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WizardStepResult> stepPeriod(@Valid @RequestBody Step7Request request) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodName(request.getPeriodName());
        period.setStartDate(request.getStartDate());
        period.setEndDate(request.getEndDate());
        periodService.createPeriod(period);
        return ok(advance(7));
    }

    // 功能：重置向导——逻辑删除进度，下次从步骤1开始
    @PutMapping("/api/v1/wizard/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> reset() {
        wizardService.reset(currentUserId());
        return ok("向导已重置", null);
    }

    private WizardStepResult advance(int step) {
        WizardProgressDTO progress = wizardService.completeStep(step, currentUserId());
        return new WizardStepResult(step, progress);
    }

    // ========================================
    // 步骤请求DTO
    // ========================================

    @Data
    public static class Step1Request {
        @NotBlank
        private String roleCode;
        @NotBlank
        private String roleName;
    }

    @Data
    public static class Step3Request {
        @NotBlank
        private String projectCode;
        @NotBlank
        private String projectName;
        @NotBlank
        private String projectStage;
        private String description;
    }

    @Data
    public static class Step4Request {
        @NotBlank
        private String projectCode;
        @NotBlank
        private String roleCode;
        @NotBlank
        private String employeeId;
    }

    @Data
    public static class Step5Request {
        @NotBlank
        private String projectRoleCode;
        @NotBlank
        private String projectStage;
        @NotBlank
        private String kpiName;
        @NotNull
        @DecimalMin("0.00") @DecimalMax("100.00")
        private BigDecimal weight;
    }

    @Data
    public static class Step6Request {
        @NotBlank
        private String category;
        @NotBlank
        private String position;
        @NotNull
        private Boolean isProjectBased;
        @NotNull
        @DecimalMin("0.00") @DecimalMax("100.00")
        private BigDecimal projectWeight;
        @NotNull
        @DecimalMin("0.00") @DecimalMax("100.00")
        private BigDecimal funcWeight;
        @Size(max = 50)
        private String funcAssessMode;
    }

    @Data
    public static class Step7Request {
        @NotBlank
        private String periodName;
        @NotNull
        private LocalDate startDate;
        @NotNull
        private LocalDate endDate;
    }

    // ========================================
    // 步骤结果
    // ========================================

    @Data
    public static class WizardStepResult {
        private final int completedStep;
        private final int nextStep;
        private final boolean wizardCompleted;

        public WizardStepResult(int completedStep, WizardProgressDTO progress) {
            this.completedStep = completedStep;
            this.nextStep = progress.getCurrentStep();
            this.wizardCompleted = progress.isCompleted();
        }
    }
}
