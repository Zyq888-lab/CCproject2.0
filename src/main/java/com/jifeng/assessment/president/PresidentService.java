// 模块用途：总裁逐项目确认业务逻辑——确认清单、单项目通过/退回、PD 重提交
// 依赖文件：ProjectConfirmationMapper.java, ProjectRoleAssignmentMapper.java, PeriodService.java, SystemParamService.java
// 修改注意：周期级翻转委托 PeriodService.tryConfirmPeriod（原子 UPDATE，并发先到先得）；退回上限 PRESIDENT_RETURN_TIMES
package com.jifeng.assessment.president;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.confirmation.ProjectConfirmation;
import com.jifeng.assessment.confirmation.ProjectConfirmationMapper;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.system.SystemParamService;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PresidentService {

    private final ProjectConfirmationMapper projectConfirmationMapper;
    private final ProjectRoleAssignmentMapper roleAssignmentMapper;
    private final SystemParamService systemParamService;
    private final SysUserMapper sysUserMapper;
    private final ProjectMapper projectMapper;
    private final PeriodService periodService;

    private static final String ROLE_PRESIDENT = "PRESIDENT";
    private static final String ROLE_PD = "PD";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_RETURNED = "RETURNED";

    // 确认清单项 DTO——含项目名；presidentConflict 标记该项目跨阶段存在多个主总裁（异常）
    public record ConfirmationItem(Long id, String periodId, String projectCode, String projectName,
                                   String status, Integer returnCount, String returnReason, LocalDateTime confirmedAt,
                                   boolean presidentConflict) {
    }

    // 功能：查询当前登录人（主总裁）的确认清单——主总裁项目 ∩ 指定周期 project_confirmation
    public List<ConfirmationItem> listConfirmations(String periodId) {
        String employeeId = currentEmployeeId();
        if (employeeId == null) {
            return List.of();
        }
        // 当前登录人为主总裁的项目编码集合
        List<String> projectCodes = roleAssignmentMapper.selectList(
                        new LambdaQueryWrapper<ProjectRoleAssignment>()
                                .eq(ProjectRoleAssignment::getEmployeeId, employeeId)
                                .eq(ProjectRoleAssignment::getProjectRoleCode, ROLE_PRESIDENT)
                                .eq(ProjectRoleAssignment::getIsPrimary, true)
                                .eq(ProjectRoleAssignment::getDeleted, 0))
                .stream()
                .map(ProjectRoleAssignment::getProjectCode)
                .distinct()
                .toList();
        if (projectCodes.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<ProjectConfirmation> wrapper = new LambdaQueryWrapper<ProjectConfirmation>()
                .in(ProjectConfirmation::getProjectCode, projectCodes);
        if (StringUtils.hasText(periodId)) {
            wrapper.eq(ProjectConfirmation::getPeriodId, periodId);
        }
        wrapper.orderByAsc(ProjectConfirmation::getId);
        List<ProjectConfirmation> confirmations = projectConfirmationMapper.selectList(wrapper);
        if (confirmations.isEmpty()) {
            return List.of();
        }

        Map<String, String> projectNames = projectMapper.selectList(
                        new LambdaQueryWrapper<Project>()
                                .in(Project::getProjectCode, projectCodes))
                .stream()
                .collect(Collectors.toMap(Project::getProjectCode, Project::getProjectName, (a, b) -> a));

        // 标记「一项目多主总裁」异常：任一项目跨阶段存在多个 distinct 主总裁工号则 flag
        Map<String, Boolean> presidentConflict = roleAssignmentMapper.selectList(
                        new LambdaQueryWrapper<ProjectRoleAssignment>()
                                .in(ProjectRoleAssignment::getProjectCode, projectCodes)
                                .eq(ProjectRoleAssignment::getProjectRoleCode, ROLE_PRESIDENT)
                                .eq(ProjectRoleAssignment::getIsPrimary, true)
                                .eq(ProjectRoleAssignment::getDeleted, 0))
                .stream()
                .collect(Collectors.groupingBy(ProjectRoleAssignment::getProjectCode,
                        Collectors.mapping(ProjectRoleAssignment::getEmployeeId, Collectors.toSet())))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size() > 1));

        return confirmations.stream()
                .map(c -> new ConfirmationItem(
                        c.getId(), c.getPeriodId(), c.getProjectCode(),
                        projectNames.get(c.getProjectCode()),
                        c.getStatus(), c.getReturnCount(), c.getReturnReason(), c.getConfirmedAt(),
                        presidentConflict.getOrDefault(c.getProjectCode(), false)))
                .toList();
    }

    // 功能：反查项目主总裁工号——委托 PeriodService（单一来源），与 enterCalibration 的 NO_PRESIDENT 判断同口径
    public String resolvePrimaryPresident(String projectCode) {
        return periodService.resolvePrimaryPresident(projectCode);
    }

    // 功能：单项目确认通过——仅主总裁可操作；全部项目通过后原子翻转周期 CALIBRATING→CONFIRMED
    @Transactional
    public void approve(Long id) {
        ProjectConfirmation confirmation = requireConfirmation(id);
        // 悲观锁：串行化同周期确认，消除「selectCount→tryConfirmPeriod」并发漏判
        periodService.lockPeriod(confirmation.getPeriodId());
        assertPresidentOf(confirmation);
        if (STATUS_APPROVED.equals(confirmation.getStatus())) {
            return; // 幂等：已通过直接返回
        }
        if (!STATUS_PENDING.equals(confirmation.getStatus())) {
            throw new BusinessException(400, "仅待确认的项目可确认通过");
        }
        confirmation.setStatus(STATUS_APPROVED);
        confirmation.setConfirmedByEmployeeId(currentEmployeeId());
        confirmation.setConfirmedAt(LocalDateTime.now());
        confirmation.setReturnReason(null);
        confirmation.setUpdatedAt(LocalDateTime.now());
        projectConfirmationMapper.updateById(confirmation);
        // 本周期无待确认/退回项目时，原子翻转周期状态
        if (noPendingOrReturned(confirmation.getPeriodId())) {
            periodService.tryConfirmPeriod(confirmation.getPeriodId());
        }
    }

    // 功能：单项目退回——附原因；return_count 达 PRESIDENT_RETURN_TIMES 上限时拒绝
    @Transactional
    public void returnProject(Long id, String reason) {
        ProjectConfirmation confirmation = requireConfirmation(id);
        // 悲观锁：串行化同周期写，与 approve 互斥，避免退回与通过并发导致漏判
        periodService.lockPeriod(confirmation.getPeriodId());
        assertPresidentOf(confirmation);
        if (!STATUS_PENDING.equals(confirmation.getStatus())) {
            throw new BusinessException(400, "仅待确认的项目可退回");
        }
        int cap = parseReturnCap();
        int next = (confirmation.getReturnCount() == null ? 0 : confirmation.getReturnCount()) + 1;
        if (next > cap) {
            throw new BusinessException(400, "退回次数已达上限(" + cap + ")，不可再退回");
        }
        confirmation.setStatus(STATUS_RETURNED);
        confirmation.setReturnCount(next);
        confirmation.setReturnReason(reason);
        confirmation.setUpdatedAt(LocalDateTime.now());
        projectConfirmationMapper.updateById(confirmation);
    }

    // 功能：重新提交——PD 重校准后 RETURNED→PENDING，清除退回原因（保留退回计数）
    @Transactional
    public void resubmit(Long id) {
        ProjectConfirmation confirmation = requireConfirmation(id);
        assertPdOf(confirmation.getProjectCode());
        if (!STATUS_RETURNED.equals(confirmation.getStatus())) {
            throw new BusinessException(400, "仅退回的项目可重提交");
        }
        confirmation.setStatus(STATUS_PENDING);
        confirmation.setReturnReason(null);
        confirmation.setUpdatedAt(LocalDateTime.now());
        projectConfirmationMapper.updateById(confirmation);
    }

    // 功能：加载确认项，不存在抛 404
    private ProjectConfirmation requireConfirmation(Long id) {
        ProjectConfirmation confirmation = projectConfirmationMapper.selectById(id);
        if (confirmation == null) {
            throw new BusinessException(404, "项目确认项不存在: " + id);
        }
        return confirmation;
    }

    // 功能：校验当前登录人是该项目主总裁，否则 403
    private void assertPresidentOf(ProjectConfirmation confirmation) {
        String president = resolvePrimaryPresident(confirmation.getProjectCode());
        String current = currentEmployeeId();
        if (president == null || !president.equals(current)) {
            throw new BusinessException(403, "仅该项目负责总裁可操作");
        }
    }

    // 功能：校验当前登录人是该项目主 PD，否则 403（PD 重提交不得跨项目）
    private void assertPdOf(String projectCode) {
        String current = currentEmployeeId();
        if (current == null || !primaryPdsOf(projectCode).contains(current)) {
            throw new BusinessException(403, "仅该项目负责 PD 可重提交");
        }
    }

    // 功能：反查项目主 PD 工号列表（所有阶段去重）——project_role_assignment（PD 且 is_primary 且未删除）
    private List<String> primaryPdsOf(String projectCode) {
        return roleAssignmentMapper.selectList(
                        new LambdaQueryWrapper<ProjectRoleAssignment>()
                                .eq(ProjectRoleAssignment::getProjectCode, projectCode)
                                .eq(ProjectRoleAssignment::getProjectRoleCode, ROLE_PD)
                                .eq(ProjectRoleAssignment::getIsPrimary, true)
                                .eq(ProjectRoleAssignment::getDeleted, 0))
                .stream()
                .map(ProjectRoleAssignment::getEmployeeId)
                .distinct()
                .toList();
    }

    // 功能：判断本周期是否已无待确认/退回项目
    private boolean noPendingOrReturned(String periodId) {
        Long count = projectConfirmationMapper.selectCount(
                new LambdaQueryWrapper<ProjectConfirmation>()
                        .eq(ProjectConfirmation::getPeriodId, periodId)
                        .in(ProjectConfirmation::getStatus, STATUS_PENDING, STATUS_RETURNED));
        return count == null || count == 0;
    }

    // 功能：解析退回次数上限——PRESIDENT_RETURN_TIMES 缺省 3
    private int parseReturnCap() {
        String value = systemParamService.getValueOrDefault("PRESIDENT_RETURN_TIMES", "3");
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    // 功能：从 SecurityContext 用户名反查当前用户 employeeId
    private String currentEmployeeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        return user != null ? user.getEmployeeId() : null;
    }
}
