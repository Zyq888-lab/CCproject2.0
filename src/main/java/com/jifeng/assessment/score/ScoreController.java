// 模块用途：考核打分REST接口——提交评分、暂存草稿、凭证上传
// 依赖文件：ScoreService.java, BaseController.java
// 修改注意：提交评分会改变task状态为SUBMITTED；草稿不改变状态
package com.jifeng.assessment.score;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.task.AssessmentTask;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ScoreController extends BaseController {

    private final ScoreService scoreService;

    // 功能：提交评分——校验完整性+范围+类型，任务状态→SUBMITTED
    @PostMapping("/tasks/{taskId}/scores")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人')")
    public ApiResponse<AssessmentTask> submit(@PathVariable Long taskId,
                                              @Valid @RequestBody SubmitRequest request) {
        return ok(scoreService.submit(taskId, request.getItems()));
    }

    // 功能：暂存草稿——可只填部分指标，不改变任务状态
    @PutMapping("/tasks/{taskId}/scores")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人')")
    public ApiResponse<Void> saveDraft(@PathVariable Long taskId,
                                       @Valid @RequestBody SubmitRequest request) {
        scoreService.saveDraft(taskId, request.getItems());
        return ok("草稿已保存", null);
    }

    // 功能：凭证上传——文件大小≤10MB，返回访问URL
    @PostMapping("/scores/{scoreId}/evidence")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人')")
    public ApiResponse<String> uploadEvidence(@PathVariable Long scoreId,
                                              @RequestParam("file") MultipartFile file) {
        return ok(scoreService.uploadEvidence(scoreId, file));
    }

    @Data
    public static class SubmitRequest {
        @NotEmpty
        private List<ScoreService.ScoreItem> items;
    }
}
