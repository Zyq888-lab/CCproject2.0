// 模块用途：岗位分类管理 REST 接口——分类列表查询、新增、修改、删除
// 依赖文件：PositionCategoryService.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/position-categories 开头，增删改需 ADMIN 角色
package com.jifeng.assessment.positioncategory;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PositionCategoryController extends BaseController {

    private final PositionCategoryService positionCategoryService;

    // 功能：获取所有分类列表——按 sort_order 升序，供前端下拉框使用
    @GetMapping("/api/v1/position-categories/list")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<List<PositionCategoryDTO>> list() {
        List<PositionCategoryDTO> result = positionCategoryService.listOrdered().stream()
                .map(pc -> {
                    PositionCategoryDTO dto = new PositionCategoryDTO();
                    dto.setId(pc.getId());
                    dto.setName(pc.getName());
                    dto.setSortOrder(pc.getSortOrder());
                    dto.setCreatedAt(pc.getCreatedAt());
                    return dto;
                })
                .toList();
        return ok(result);
    }

    // 功能：新增分类——名称唯一，sortOrder 可选默认为 0
    @PostMapping("/api/v1/position-categories")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PositionCategoryDTO> create(@Valid @RequestBody CreateRequest request) {
        PositionCategory entity = positionCategoryService.create(request.getName(), request.getSortOrder());
        PositionCategoryDTO dto = new PositionCategoryDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setSortOrder(entity.getSortOrder());
        dto.setCreatedAt(entity.getCreatedAt());
        return ok(dto);
    }

    // 功能：修改分类——乐观锁防并发覆盖
    @PutMapping("/api/v1/position-categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PositionCategoryDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRequest request) {
        PositionCategory entity = positionCategoryService.update(id, request.getName(), request.getSortOrder());
        PositionCategoryDTO dto = new PositionCategoryDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setSortOrder(entity.getSortOrder());
        dto.setCreatedAt(entity.getCreatedAt());
        return ok(dto);
    }

    // 功能：删除分类——引用检查，被业务表引用则拒绝
    @DeleteMapping("/api/v1/position-categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        positionCategoryService.deleteCategory(id);
        return ok("已删除", null);
    }

    @Data
    public static class CreateRequest {
        @NotBlank(message = "分类名称不能为空")
        private String name;
        private Integer sortOrder;
    }

    @Data
    public static class UpdateRequest {
        @NotBlank(message = "分类名称不能为空")
        private String name;
        private Integer sortOrder;
    }
}
