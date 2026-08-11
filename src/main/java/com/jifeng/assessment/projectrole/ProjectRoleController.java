// 模块用途：项目角色管理REST接口——提供角色CRUD、启用停用API
// 依赖文件：ProjectRoleService.java, ProjectRoleDTO.java, ProjectRole.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/project-roles 开头，返回值统一用 ApiResponse 包装
package com.jifeng.assessment.projectrole;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/project-roles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ProjectRoleController extends BaseController {

    private final ProjectRoleService projectRoleService;

    // 功能：分页查询项目角色，支持 roleCode/roleName 模糊搜索和 isActive 筛选
    @GetMapping
    public ApiResponse<PageResult<ProjectRoleDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) Boolean isActive) {
        return ok(projectRoleService.listProjectRoles(page, size, roleCode, roleName, isActive));
    }

    // 功能：批量导入项目角色
    @PostMapping("/import")
    public ApiResponse<PageResult<ProjectRoleDTO>> importRoles(@RequestBody List<ProjectRole> roles) {
        return ok(projectRoleService.importRoles(roles));
    }

    // 功能：新增项目角色——校验 roleCode 非空且不重复
    @PostMapping
    public ApiResponse<ProjectRoleDTO> create(@Valid @RequestBody ProjectRole role) {
        return ok(projectRoleService.createProjectRole(role));
    }

    // 功能：修改角色名称/描述——使用乐观锁防止并发覆盖，roleCode不可修改
    @PutMapping("/{roleCode}")
    public ApiResponse<ProjectRoleDTO> update(
            @PathVariable String roleCode,
            @RequestBody ProjectRole update) {
        return ok(projectRoleService.updateProjectRole(roleCode, update));
    }

    // 功能：删除角色——检查岗位配置引用，有引用则拒绝
    @DeleteMapping("/{roleCode}")
    public ApiResponse<Void> delete(@PathVariable String roleCode) {
        projectRoleService.deleteProjectRole(roleCode);
        return ok("已删除", null);
    }

    // 功能：启用/停用角色——切换 is_active 状态
    @PutMapping("/{roleCode}/toggle")
    public ApiResponse<ProjectRoleDTO> toggle(@PathVariable String roleCode) {
        return ok(projectRoleService.toggleProjectRole(roleCode));
    }
}
