// 模块用途：用户管理REST接口——提供用户CRUD的分页列表、创建、角色分配API
// 依赖文件：UserService.java, UserDTO.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/users 开头，返回值统一用 ApiResponse 包装
package com.jifeng.assessment.user;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController extends BaseController {

    private final UserService userService;

    // 功能：分页查询用户列表，返回用户信息含关联员工姓名和角色列表
    @GetMapping
    public ApiResponse<PageResult<UserDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setSize(size);
        return ok(userService.listUsers(query));
    }

    // 功能：创建系统用户——关联员工工号、设置用户名和初始密码，密码bcrypt(12)加密存储
    @PostMapping
    public ApiResponse<UserDTO> create(@Valid @RequestBody CreateUserRequest request) {
        return ok(userService.createUser(
                request.getEmployeeId(),
                request.getUsername(),
                request.getPassword()));
    }

    // 功能：覆盖式更新用户角色——先删除原有角色再插入新角色，返回更新后的角色列表
    @PutMapping("/{userId}/roles")
    public ApiResponse<List<String>> updateRoles(
            @PathVariable String userId,
            @Valid @RequestBody UpdateRolesRequest request) {
        return ok(userService.updateUserRoles(userId, request.getRoleTypes()));
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank
        private String employeeId;
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }

    @Data
    public static class UpdateRolesRequest {
        @NotEmpty
        private List<String> roleTypes;
    }
}
