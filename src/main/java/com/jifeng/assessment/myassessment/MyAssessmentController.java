// 模块用途：我的考核REST接口——员工查询自己的项目考核任务及KPI
// 依赖文件：MyAssessmentService.java, BaseController.java
// 修改注意：仅员工角色可见（hasRole('员工')，被额外分配员工角色的 ADMIN/PM 亦可见）
package com.jifeng.assessment.myassessment;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/my-assessment")
@RequiredArgsConstructor
public class MyAssessmentController extends BaseController {

    private final MyAssessmentService myAssessmentService;

    // 功能：查询当前登录员工的项目考核任务（项目/状态/评估人/KPI）
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PM','PD','评估人','员工')")
    public ApiResponse<List<MyAssessmentItem>> myAssessment() {
        return ok(myAssessmentService.getMyAssessment());
    }
}
