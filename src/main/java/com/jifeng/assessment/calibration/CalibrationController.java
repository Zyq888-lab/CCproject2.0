// 模块用途：PD 校准 REST 接口——校准矩阵查询 + 行内改分
// 依赖文件：CalibrationService.java, BaseController.java
// 修改注意：ADMIN/PD 均可访问；改分仅 CALIBRATING 周期有效（服务层校验），
//   409 冲突由前端按乐观锁冲突弹窗处理
package com.jifeng.assessment.calibration;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
public class CalibrationController extends BaseController {

    private final CalibrationService calibrationService;

    // 功能：校准矩阵——分布汇总带 + 离群优先排序的员工结果行 + 未提交人数告警
    @GetMapping("/api/v1/periods/{periodId}/calibration")
    @PreAuthorize("hasAnyRole('ADMIN', 'PD')")
    public ApiResponse<CalibrationMatrixResponse> matrix(@PathVariable String periodId) {
        return ok(calibrationService.getCalibrationMatrix(periodId));
    }

    // 功能：行内改分——写 adjusted_score + 追加审计行；409 为乐观锁冲突
    @PutMapping("/api/v1/periods/{periodId}/calibration/adjust")
    @PreAuthorize("hasAnyRole('ADMIN', 'PD')")
    public ApiResponse<Void> adjust(@PathVariable String periodId,
                                    @Valid @RequestBody AdjustRequest request) {
        calibrationService.adjust(periodId, request.getAssesseeId(), request.getNewScore(), request.getReason());
        return ok(null);
    }

    @Data
    public static class AdjustRequest {
        @NotBlank
        private String assesseeId;
        @NotNull
        private BigDecimal newScore;
        @NotBlank
        private String reason;
    }
}
