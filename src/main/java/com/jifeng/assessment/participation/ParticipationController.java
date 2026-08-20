// 模块用途：项目参与REST接口——查询参与列表、员工填写参与、PM审批
// 依赖文件：ParticipationService.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/participations 开头，审批仅 PENDING 状态可操作
package com.jifeng.assessment.participation;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/participations")
@RequiredArgsConstructor
public class ParticipationController extends BaseController {

    private final ParticipationService participationService;

    // 功能：分页查询项目参与列表——员工看自己的，PM/PD/评估人按项目范围，ADMIN看全部
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<PageResult<EmployeeProjectParticipation>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String periodId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String employeeId) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setSize(size);
        return ok(participationService.listParticipations(query, periodId, status, employeeId));
    }

    // 功能：员工填写项目参与——投入比重总和=100%，可一次填多项目
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<List<EmployeeProjectParticipation>> create(
            @Valid @RequestBody CreateRequest request) {
        String employeeId = request.getEmployeeId() != null
                ? request.getEmployeeId()
                : participationService.getCurrentEmployeeId();
        return ok(participationService.create(employeeId, request.getPeriodId(), request.getItems()));
    }

    // 功能：PM审批项目参与——通过/不通过，可填建议投入比重与审批意见
    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD')")
    public ApiResponse<EmployeeProjectParticipation> approve(
            @PathVariable Long id,
            @Valid @RequestBody ApprovalRequest request) {
        return ok(participationService.approve(id, request.getApproved(), request.getSuggestedRate(), request.getComment()));
    }

    // 功能：员工重新提交被拒绝的参与申请——可更新投入比重，状态重置为 PENDING 待审批
    @PostMapping("/{id}/resubmit")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<EmployeeProjectParticipation> resubmit(
            @PathVariable Long id,
            @RequestBody(required = false) ResubmitRequest request) {
        BigDecimal participationRate = request != null ? request.getParticipationRate() : null;
        return ok(participationService.resubmit(id, participationRate));
    }

    // 功能：ADMIN 删除参与记录——逻辑删除（@TableLogic），便于清理测试脏数据
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        participationService.delete(id);
        return ok("已删除", null);
    }

    @Data
    public static class CreateRequest {
        @NotBlank
        private String periodId;
        private String employeeId;
        @NotEmpty
        private List<ProjectParticipationItem> items;
    }

    @Data
    public static class ApprovalRequest {
        @NotNull
        private Boolean approved;
        private BigDecimal suggestedRate;
        private String comment;
    }

    @Data
    public static class ResubmitRequest {
        private BigDecimal participationRate;
    }
}
