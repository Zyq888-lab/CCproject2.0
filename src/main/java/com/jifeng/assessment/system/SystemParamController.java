// 模块用途：系统参数REST接口——查询全部参数、批量更新
// 依赖文件：SystemParamService.java, BaseController.java
// 修改注意：接口路径 /api/v1/system-params，批量更新一次可改多个参数
package com.jifeng.assessment.system;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class SystemParamController extends BaseController {

    private final SystemParamService systemParamService;

    // 功能：查询所有系统参数
    @GetMapping("/api/v1/system-params")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<List<SystemParam>> list() {
        return ok(systemParamService.listAll());
    }

    // 功能：批量更新系统参数
    @PutMapping("/api/v1/system-params")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> batchUpdate(@Valid @RequestBody List<ParamUpdateRequest> requests) {
        List<SystemParam> updates = requests.stream().map(r -> {
            SystemParam p = new SystemParam();
            p.setId(r.getId());
            p.setParamValue(r.getParamValue());
            p.setVersion(r.getVersion());
            return p;
        }).toList();
        systemParamService.batchUpdate(updates);
        return ok("已更新", null);
    }

    @Data
    public static class ParamUpdateRequest {
        @NotNull
        private Long id;
        @NotBlank
        private String paramValue;
        @NotNull
        private Long version;
    }
}
