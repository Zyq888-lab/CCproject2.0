// 模块用途：周期监控REST接口——查询某周期下所有考核任务的监控聚合列表
// 依赖文件：PeriodMonitorService.java, BaseController.java
// 修改注意：仅 ADMIN/PM 可访问；PM 数据范围由服务层按自己项目过滤
package com.jifeng.assessment.monitor;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PeriodMonitorController extends BaseController {

    private final PeriodMonitorService periodMonitorService;

    // 功能：查询周期监控列表——ADMIN 全见，PM 仅见自己项目
    @GetMapping("/api/v1/periods/{periodId}/monitor")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<PeriodMonitorItem>> monitor(@PathVariable String periodId) {
        return ok(periodMonitorService.monitor(periodId));
    }
}
