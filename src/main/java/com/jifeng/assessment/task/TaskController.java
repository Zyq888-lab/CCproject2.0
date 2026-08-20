// 模块用途：考核任务REST接口——查询任务列表、发起考核、开始评分、取消任务
// 依赖文件：TaskService.java, TaskGeneratorService.java, BaseController.java
// 修改注意：发起考核仅 ADMIN 可操作；开始/取消经状态机校验，非法转换返回400
package com.jifeng.assessment.task;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController extends BaseController {

    private final TaskService taskService;
    private final TaskGeneratorService taskGeneratorService;

    // 功能：分页查询考核任务列表——评估人看待评分，员工看进度，PM/PD/ADMIN按条件筛选
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<PageResult<AssessmentTask>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String periodId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String assessorId,
            @RequestParam(required = false) String assesseeId,
            @RequestParam(required = false) String scope) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setSize(size);
        return ok(taskService.listTasks(query, periodId, status, projectCode, assessorId, assesseeId, scope));
    }

    // 功能：查询任务详情——返回任务 + 关联 KPI 指标列表（打分页加载用）
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<AssessmentTask> detail(@PathVariable Long id) {
        return ok(taskService.getTaskDetail(id));
    }

    // 功能：开始评分——PENDING → IN_PROGRESS
    @PutMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<AssessmentTask> start(@PathVariable Long id) {
        return ok(taskService.start(id));
    }

    // 功能：取消任务——→ CANCELED（员工离职/项目取消场景）
    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AssessmentTask> cancel(@PathVariable Long id) {
        return ok(taskService.cancel(id));
    }

    // 功能：发起考核——ADMIN 触发，批量生成考核任务并返回差异报告统计
    @PostMapping("/{periodId}/launch")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TaskGeneratorService.LaunchResult> launch(@PathVariable String periodId) {
        return ok(taskGeneratorService.launch(periodId));
    }
}
