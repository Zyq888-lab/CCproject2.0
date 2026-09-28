// 模块用途：员工考核结果 REST 接口——查询本人（或 ADMIN 指定员工）的最终结果
// 依赖文件：ResultService.java, PeriodService.java, BaseController.java
// 修改注意：结果仅 PUBLISHED/COMPLETED 可见（isResultVisible）；非 ADMIN 强制本人视角，
//   ADMIN 可传 assesseeId 查看任意员工；未发布抛 403
package com.jifeng.assessment.result;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ResultController extends BaseController {

    private final PeriodService periodService;
    private final ResultService resultService;
    private final SysUserMapper sysUserMapper;

    // 功能：查询考核结果——结果分=adjusted_score；未发布(PUBLISHED/COMPLETED 之外)抛 403
    @GetMapping("/api/v1/periods/{periodId}/result")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'PD', '评估人', '员工')")
    public ApiResponse<EmployeeResultResponse> result(@PathVariable String periodId,
                                                      @RequestParam(required = false) String assesseeId) {
        if (!periodService.isResultVisible(periodId)) {
            throw new BusinessException(403, "考核结果尚未发布，暂不可查看");
        }
        String target = resolveTargetAssessee(assesseeId);
        if (!StringUtils.hasText(target)) {
            throw new BusinessException(403, "当前账号未关联员工，无法查看考核结果");
        }
        return ok(resultService.getEmployeeResult(periodId, target));
    }

    // 功能：确定目标被考核人——ADMIN 可传 assesseeId 查看任意员工，其他角色强制本人
    private String resolveTargetAssessee(String assesseeId) {
        if (isAdmin() && StringUtils.hasText(assesseeId)) {
            return assesseeId;
        }
        return getCurrentEmployeeId();
    }

    // 功能：判断当前登录用户是否 ADMIN
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // 功能：登录用户名 → 员工工号（与 ScoreService/CalibrationService 同源）
    private String getCurrentEmployeeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        return user != null ? user.getEmployeeId() : null;
    }
}
