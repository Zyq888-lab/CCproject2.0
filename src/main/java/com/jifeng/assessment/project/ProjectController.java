// 模块用途：项目管理REST接口——提供项目CRUD、阶段确认/重置API
// 依赖文件：ProjectService.java, ProjectDTO.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/projects 开头，返回值统一用 ApiResponse 包装
package com.jifeng.assessment.project;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController extends BaseController {

    private final ProjectService projectService;

    // 功能：分页查询项目列表，可选 ?stage=&status= 筛选
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD')")
    public ApiResponse<PageResult<ProjectDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") Boolean includeInactive) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setSize(size);
        return ok(projectService.listProjects(query, stage, status, includeInactive));
    }

    // 功能：创建项目——校验 projectCode 非空不重复，projectStage 为有效枚举值
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProjectDTO> create(@Valid @RequestBody Project project) {
        return ok(projectService.createProject(project));
    }

    // 功能：PM确认项目阶段——使用乐观锁防止并发覆盖，已确认则拒绝
    @PutMapping("/{projectCode}/{projectStage}/confirm-stage")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<ProjectDTO> confirmStage(@PathVariable String projectCode, @PathVariable String projectStage) {
        return ok(projectService.confirmStage(projectCode, projectStage));
    }

    // 功能：ADMIN强制重置阶段确认——使用乐观锁，清空确认人和时间
    @PutMapping("/{projectCode}/{projectStage}/reset-stage")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProjectDTO> resetStage(@PathVariable String projectCode, @PathVariable String projectStage) {
        return ok(projectService.resetStage(projectCode, projectStage));
    }

    // 功能：归档已完成的项目阶段——只有 COMPLETED 状态可归档，变为 INACTIVE
    @PutMapping("/{projectCode}/{projectStage}/archive")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<ProjectDTO> archiveStage(@PathVariable String projectCode, @PathVariable String projectStage) {
        return ok(projectService.archiveStage(projectCode, projectStage));
    }
}
