// 模块用途：仪表盘REST接口——配置进度 + 差异报告
// 依赖文件：DashboardService.java, BaseController.java
// 修改注意：阶段2扩充diffReport返回类型时同步更新
package com.jifeng.assessment.dashboard;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class DashboardController extends BaseController {

    private final DashboardService dashboardService;

    // 功能：仪表盘摘要——聚合所有配置模块的统计数据和完成百分比
    @GetMapping("/api/v1/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<DashboardService.DashboardSummary> dashboard() {
        return ok(dashboardService.summary());
    }

    // 功能：返回各配置模块的数据量和配置状态
    @GetMapping("/api/v1/dashboard/config-progress")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<DashboardService.ConfigProgressItem>> configProgress() {
        return ok(dashboardService.configProgress());
    }

    // 功能：返回考核差异报告——阶段2批量生成后使用，阶段1返回空列表
    @GetMapping("/api/v1/dashboard/diff-report")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<String>> diffReport() {
        return ok(dashboardService.diffReport());
    }
}
