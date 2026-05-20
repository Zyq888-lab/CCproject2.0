// 模块用途：考核周期REST接口——查询列表、创建周期、关闭周期
// 依赖文件：PeriodService.java, BaseController.java
// 修改注意：创建时后端自动生成periodId，关闭后不可再修改
package com.jifeng.assessment.period;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class PeriodController extends BaseController {

    private final PeriodService periodService;

    // 功能：查询考核周期列表，支持按状态筛选
    @GetMapping("/api/v1/periods")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ApiResponse<List<AssessmentPeriod>> list(
            @RequestParam(required = false) String status) {
        return ok(periodService.listPeriods(status));
    }

    // 功能：创建考核周期——需先关闭当前活跃周期
    @PostMapping("/api/v1/periods")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AssessmentPeriod> create(@Valid @RequestBody CreateRequest request) {
        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodName(request.getPeriodName());
        period.setStartDate(request.getStartDate());
        period.setEndDate(request.getEndDate());
        return ok(periodService.createPeriod(period));
    }

    // 功能：关闭考核周期——状态变为COMPLETED
    @PutMapping("/api/v1/periods/{periodId}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AssessmentPeriod> close(@PathVariable String periodId) {
        return ok(periodService.closePeriod(periodId));
    }

    @Data
    public static class CreateRequest {
        @NotBlank
        private String periodName;
        @NotNull
        private LocalDate startDate;
        @NotNull
        private LocalDate endDate;
    }
}
