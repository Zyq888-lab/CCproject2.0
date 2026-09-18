// 模块用途：总裁确认REST接口——确认清单、单项目通过/退回、PD 重提交
// 依赖文件：PresidentService.java, BaseController.java
// 修改注意：通过/退回限「总裁」角色，重提交限「PD」角色；周期发布由 PeriodController.publish 承担
package com.jifeng.assessment.president;

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
public class PresidentController extends BaseController {

    private final PresidentService presidentService;

    // 功能：查询当前总裁的确认清单（可选按周期过滤）
    @GetMapping("/api/v1/president/confirmations")
    @PreAuthorize("hasRole('总裁')")
    public ApiResponse<List<PresidentService.ConfirmationItem>> confirmations(
            @RequestParam(required = false) String periodId) {
        return ok(presidentService.listConfirmations(periodId));
    }

    // 功能：单项目确认通过
    @PostMapping("/api/v1/president/confirmations/{id}/approve")
    @PreAuthorize("hasRole('总裁')")
    public ApiResponse<Void> approve(@PathVariable Long id) {
        presidentService.approve(id);
        return ok(null);
    }

    // 功能：单项目退回（附原因）
    @PostMapping("/api/v1/president/confirmations/{id}/return")
    @PreAuthorize("hasRole('总裁')")
    public ApiResponse<Void> returnProject(@PathVariable Long id, @Valid @RequestBody ReturnRequest request) {
        presidentService.returnProject(id, request.getReason());
        return ok(null);
    }

    // 功能：PD 重校准后重新提交
    @PostMapping("/api/v1/president/confirmations/{id}/resubmit")
    @PreAuthorize("hasRole('PD')")
    public ApiResponse<Void> resubmit(@PathVariable Long id) {
        presidentService.resubmit(id);
        return ok(null);
    }

    @Data
    public static class ReturnRequest {
        @NotBlank
        private String reason;
    }
}
