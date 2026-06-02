// 模块用途：项目角色分配REST接口——提供分配人员、标记PD负责人、移除分配、汇总查询API
// 依赖文件：RoleAssignmentService.java, ProjectRoleAssignmentDTO.java, ProjectRoleAssignmentSummaryDTO.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/projects/{projectCode}/assignments 开头，汇总接口路径为 /api/v1/projects/assignments/summary
package com.jifeng.assessment.roleassignment;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RoleAssignmentController extends BaseController {

    private final RoleAssignmentService roleAssignmentService;

    // 功能：查询项目下所有角色分配，返回含员工姓名的分配列表
    @GetMapping("/api/v1/projects/{projectCode}/assignments")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD')")
    public ApiResponse<List<ProjectRoleAssignmentDTO>> list(@PathVariable String projectCode) {
        return ok(roleAssignmentService.listAssignments(projectCode));
    }

    // 功能：跨项目角色分配汇总查询——四表JOIN，支持多条件筛选和分页
    @GetMapping("/api/v1/projects/assignments/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD')")
    public ApiResponse<PageResult<ProjectRoleAssignmentSummaryDTO>> listSummary(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String projectStage,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) Boolean isPrimaryPd) {
        return ok(roleAssignmentService.listSummary(page, size,
                projectCode, projectStage, roleCode, employeeId, isPrimaryPd));
    }

    // 功能：分配员工到项目角色——校验项目、角色、员工均存在且未被重复分配
    @PostMapping("/api/v1/projects/{projectCode}/assignments")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<ProjectRoleAssignmentDTO> assign(
            @PathVariable String projectCode,
            @Valid @RequestBody AssignRequest request) {
        return ok(roleAssignmentService.assignEmployee(
                projectCode, request.getRoleCode(), request.getEmployeeId()));
    }

    // 功能：标记为PD负责人——任意角色分配均可标记，先取消同项目已有PD负责人，再设置当前分配
    @PutMapping("/api/v1/projects/{projectCode}/assignments/{assignmentId}/toggle-primary-pd")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<ProjectRoleAssignmentDTO> markPrimaryPd(
            @PathVariable String projectCode,
            @PathVariable Long assignmentId) {
        return ok(roleAssignmentService.markPrimaryPd(assignmentId));
    }

    // 功能：移除角色分配——逻辑删除
    @DeleteMapping("/api/v1/projects/{projectCode}/assignments/{assignmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<Void> remove(
            @PathVariable String projectCode,
            @PathVariable Long assignmentId) {
        roleAssignmentService.removeAssignment(assignmentId);
        return ok("已移除", null);
    }

    @Data
    public static class AssignRequest {
        @NotBlank
        private String roleCode;
        @NotBlank
        private String employeeId;
    }
}
