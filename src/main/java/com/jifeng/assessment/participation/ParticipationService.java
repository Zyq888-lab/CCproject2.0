// 模块用途：项目参与业务逻辑——员工填写多项目参与(投入比重)、PM审批通过/不通过
// 依赖文件：ParticipationMapper.java, EmployeeProjectParticipation.java, BaseService.java, SysUserMapper.java
// 修改注意：投入比重总和必须=100%，单项≥1%；审批与考核任务生成在同一事务中(见 approve 方法 TODO)
package com.jifeng.assessment.participation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.notification.Notification;
import com.jifeng.assessment.notification.NotificationService;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.task.TaskGeneratorService;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ParticipationService extends BaseService<ParticipationMapper, EmployeeProjectParticipation> {

    private final SysUserMapper sysUserMapper;
    private final TaskGeneratorService taskGeneratorService;
    private final NotificationService notificationService;
    private final ProjectRoleAssignmentMapper projectRoleAssignmentMapper;
    private final PeriodService periodService;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ONE = BigDecimal.ONE;

    // 功能：分页查询项目参与列表（默认「查看」视角）——兼容旧调用，scope 缺省为 null
    public PageResult<EmployeeProjectParticipation> listParticipations(PageQuery query, String periodId, String status, String employeeId) {
        return listParticipations(query, periodId, status, employeeId, null);
    }

    // 功能：分页查询项目参与列表——按当前用户角色强制数据隔离，同时支持按周期和状态筛选。
    //   scope=approval 为「审批」视角：仅返回当前用户可审批的参与记录（主 PM 主负责的项目+阶段），
    //     不含本人提交（本人提交由该项目主 PM 审批），与 approve 的权限范围严格一致；
    //   默认(null)为「查看」视角：员工看本人(employeeId=当前工号)，主PM/主PD看主负责项目，ADMIN看全部。
    //   ADMIN 在两种视角下均看全部(可选传 employeeId 下钻)。非 ADMIN 忽略传入的 employeeId。
    //   评估人/主PD不授予审批能力——审批视角仅主PM可见；查看视角二者仅能通过「员工」角色看本人提交。
    public PageResult<EmployeeProjectParticipation> listParticipations(PageQuery query, String periodId, String status, String employeeId, String scope) {
        LambdaQueryWrapper<EmployeeProjectParticipation> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(periodId)) {
            wrapper.eq(EmployeeProjectParticipation::getPeriodId, periodId);
        }
        boolean approvalScope = "approval".equalsIgnoreCase(scope);
        if (approvalScope) {
            // 待审批视角只允许待处理记录；传入其他状态也不能查询出已处理的审批历史
            if (StringUtils.hasText(status) && !"PENDING".equalsIgnoreCase(status)) {
                return PageResult.of(0, query.getPage(), query.getSize(), List.of());
            }
            wrapper.eq(EmployeeProjectParticipation::getStatus, "PENDING");
        } else if (StringUtils.hasText(status)) {
            wrapper.eq(EmployeeProjectParticipation::getStatus, status);
        }

        Set<String> roles = getRoles();
        String currentEmployeeId = getCurrentEmployeeId();

        if (roles.contains("ADMIN")) {
            // ADMIN：看全部，可选按 employeeId 下钻
            if (StringUtils.hasText(employeeId)) {
                wrapper.eq(EmployeeProjectParticipation::getEmployeeId, employeeId);
            }
        } else if (approvalScope) {
            // 审批视角：仅主 PM 主负责 (项目, 阶段) 的参与记录可审批，与 approve 的阶段级主 PM 校验对齐；
            //   不含本人提交（hasOwn）与主 PD 可见性，避免「列表可见但审批 403」的越权展示
            if (!roles.contains("PM")) {
                return PageResult.of(0, query.getPage(), query.getSize(), List.of());
            }
            List<ProjectRoleAssignment> pmAssignments = listPrimaryAssignments(currentEmployeeId, "PM");
            if (pmAssignments.isEmpty()) {
                return PageResult.of(0, query.getPage(), query.getSize(), List.of());
            }
            wrapper.and(w -> {
                boolean first = true;
                for (ProjectRoleAssignment a : pmAssignments) {
                    if (first) {
                        w.eq(EmployeeProjectParticipation::getProjectCode, a.getProjectCode())
                         .eq(EmployeeProjectParticipation::getProjectStage, a.getProjectStage());
                        first = false;
                    } else {
                        w.or().eq(EmployeeProjectParticipation::getProjectCode, a.getProjectCode())
                                .eq(EmployeeProjectParticipation::getProjectStage, a.getProjectStage());
                    }
                }
            });
        } else {
            // 默认（查看）视角：多角色取并集——员工看本人、主PM/主PD看主负责项目
            boolean hasOwn = roles.contains("员工") && StringUtils.hasText(currentEmployeeId);
            List<ProjectRoleAssignment> primaryAssignments = new ArrayList<>();
            if (roles.contains("PM")) {
                primaryAssignments.addAll(listPrimaryAssignments(currentEmployeeId, "PM"));
            }
            if (roles.contains("PD")) {
                primaryAssignments.addAll(listPrimaryAssignments(currentEmployeeId, "PD"));
            }

            if (!hasOwn && primaryAssignments.isEmpty()) {
                return PageResult.of(0, query.getPage(), query.getSize(), List.of());
            }
            wrapper.and(w -> {
                boolean first = true;
                if (hasOwn) {
                    w.eq(EmployeeProjectParticipation::getEmployeeId, currentEmployeeId);
                    first = false;
                }
                // 阶段级隔离：主 PM/主 PD 仅见自己主负责 (项目, 阶段) 组合上的参与记录
                for (ProjectRoleAssignment a : primaryAssignments) {
                    if (first) {
                        w.eq(EmployeeProjectParticipation::getProjectCode, a.getProjectCode())
                         .eq(EmployeeProjectParticipation::getProjectStage, a.getProjectStage());
                        first = false;
                    } else {
                        w.or().eq(EmployeeProjectParticipation::getProjectCode, a.getProjectCode())
                                .eq(EmployeeProjectParticipation::getProjectStage, a.getProjectStage());
                    }
                }
            });
        }

        wrapper.orderByDesc(EmployeeProjectParticipation::getId);
        PageResult<EmployeeProjectParticipation> page = selectPage(query, wrapper);
        fillCurrentApprover(page.getList());
        return page;
    }

    // 功能：查询当前员工主负责的项目角色分配集合——project_role_code 匹配当前角色 AND is_primary=true，用于主 PM/主 PD 数据隔离
    private List<ProjectRoleAssignment> listPrimaryAssignments(String employeeId, String roleCode) {
        if (!StringUtils.hasText(employeeId) || !StringUtils.hasText(roleCode)) {
            return List.of();
        }
        return projectRoleAssignmentMapper.selectList(new LambdaQueryWrapper<ProjectRoleAssignment>()
                .eq(ProjectRoleAssignment::getEmployeeId, employeeId)
                .eq(ProjectRoleAssignment::getProjectRoleCode, roleCode)
                .eq(ProjectRoleAssignment::getIsPrimary, true)
                .eq(ProjectRoleAssignment::getDeleted, 0));
    }

    // 功能：批量反查本页参与记录所属 (projectCode, projectStage) 的主 PM 工号，填充 currentApproverEmployeeId
    private void fillCurrentApprover(List<EmployeeProjectParticipation> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<String> keys = list.stream()
                .filter(p -> StringUtils.hasText(p.getProjectCode()) && StringUtils.hasText(p.getProjectStage()))
                .map(p -> p.getProjectCode() + "|" + p.getProjectStage())
                .collect(Collectors.toSet());
        if (keys.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<ProjectRoleAssignment> wrapper = new LambdaQueryWrapper<ProjectRoleAssignment>()
                .eq(ProjectRoleAssignment::getProjectRoleCode, "PM")
                .eq(ProjectRoleAssignment::getIsPrimary, true)
                .eq(ProjectRoleAssignment::getDeleted, 0);
        wrapper.and(w -> {
            boolean first = true;
            for (String key : keys) {
                String[] parts = key.split("\\|", 2);
                String code = parts[0];
                String stage = parts.length > 1 ? parts[1] : "";
                if (first) {
                    w.eq(ProjectRoleAssignment::getProjectCode, code)
                     .eq(ProjectRoleAssignment::getProjectStage, stage);
                    first = false;
                } else {
                    w.or().eq(ProjectRoleAssignment::getProjectCode, code)
                           .eq(ProjectRoleAssignment::getProjectStage, stage);
                }
            }
        });
        List<ProjectRoleAssignment> assignments = projectRoleAssignmentMapper.selectList(wrapper);
        Map<String, String> primaryPmByKey = assignments.stream()
                .filter(a -> StringUtils.hasText(a.getEmployeeId()))
                .collect(Collectors.toMap(
                        a -> a.getProjectCode() + "|" + a.getProjectStage(),
                        ProjectRoleAssignment::getEmployeeId,
                        (a, b) -> a)); // 同 (code,stage) 多主 PM 时取第一个
        for (EmployeeProjectParticipation p : list) {
            p.setCurrentApproverEmployeeId(primaryPmByKey.get(p.getProjectCode() + "|" + p.getProjectStage()));
        }
    }

    // 功能：获取当前用户主角色——取权限列表中第一个匹配的已知角色，未认证返回空
    private String getPrimaryRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "";
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String a = authority.getAuthority();
            for (String role : new String[]{"ADMIN", "PM", "PD", "评估人", "员工"}) {
                if (a.equals("ROLE_" + role)) {
                    return role;
                }
            }
        }
        return "";
    }

    // 功能：获取当前用户拥有的全部角色集合——供多角色数据隔离的并集计算使用（区别于 getPrimaryRole 的单角色坍缩）
    private Set<String> getRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String a = authority.getAuthority();
            for (String role : new String[]{"ADMIN", "PM", "PD", "评估人", "员工"}) {
                if (a.equals("ROLE_" + role)) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }

    // 功能：员工填写项目参与——校验投入比重总和=100%、单项≥1%，逐条插入为 PENDING 状态
    @Transactional
    public List<EmployeeProjectParticipation> create(String employeeId, String periodId,
                                                     List<ProjectParticipationItem> items) {
        // 数据隔离：非 ADMIN 强制使用当前登录用户的工号，忽略调用方传入的 employeeId，防止越权代他人提交参与
        if (!"ADMIN".equals(getPrimaryRole())) {
            employeeId = getCurrentEmployeeId();
        }
        if (!StringUtils.hasText(employeeId)) {
            throw new BusinessException(400, "员工工号不能为空");
        }
        if (!StringUtils.hasText(periodId)) {
            throw new BusinessException(400, "考核周期不能为空");
        }
        // 周期锁定：INIT/ONGOING 可填写项目参与（CALIBRATING/CONFIRMED/COMPLETED 均拒绝，校准期冻结）
        periodService.assertParticipatable(periodId, "填写项目参与");
        if (items == null || items.isEmpty()) {
            throw new BusinessException(400, "至少填写一个项目的参与记录");
        }

        // 数据隔离：提交的 (projectCode, projectStage) 必须存在于该员工的项目角色分配中，否则拒绝
        List<ProjectRoleAssignment> assignments = projectRoleAssignmentMapper.selectList(
                new LambdaQueryWrapper<ProjectRoleAssignment>()
                        .eq(ProjectRoleAssignment::getEmployeeId, employeeId)
                        .eq(ProjectRoleAssignment::getDeleted, 0));
        Set<String> assignedKeys = assignments.stream()
                .map(a -> a.getProjectCode() + "|" + a.getProjectStage())
                .collect(Collectors.toSet());
        for (ProjectParticipationItem item : items) {
            if (!assignedKeys.contains(item.getProjectCode() + "|" + item.getProjectStage())) {
                throw new BusinessException(400,
                        "项目 " + item.getProjectCode() + " 阶段 " + item.getProjectStage()
                                + " 未分配给该员工，无法填写参与");
            }
        }

        // 投入比重总和校验：所有项目比重之和必须 = 100%
        BigDecimal total = BigDecimal.ZERO;
        for (ProjectParticipationItem item : items) {
            BigDecimal rate = item.getParticipationRate();
            if (rate == null || rate.compareTo(ONE) < 0) {
                throw new BusinessException(400, "单个项目投入比重不能小于1%");
            }
            total = total.add(rate);
        }
        if (total.subtract(ONE_HUNDRED).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new BusinessException(400, "投入比重总和必须等于100%，当前: " + total + "%");
        }

        LocalDateTime now = LocalDateTime.now();
        Set<String> notifiedProjectCodes = new LinkedHashSet<>();
        for (ProjectParticipationItem item : items) {
            // 存在性检查：同一员工+周期+项目+阶段 已有待审批或已通过记录时阻止新建（同项目不同阶段可分别参与）
            long pendingOrApproved = baseMapper.selectCount(new LambdaQueryWrapper<EmployeeProjectParticipation>()
                    .eq(EmployeeProjectParticipation::getEmployeeId, employeeId)
                    .eq(EmployeeProjectParticipation::getPeriodId, periodId)
                    .eq(EmployeeProjectParticipation::getProjectCode, item.getProjectCode())
                    .eq(EmployeeProjectParticipation::getProjectStage, item.getProjectStage())
                    .in(EmployeeProjectParticipation::getStatus, "PENDING", "APPROVED"));
            if (pendingOrApproved > 0) {
                throw new BusinessException(409,
                        "项目" + item.getProjectCode() + " 已存在待审批或已通过的参与记录，请勿重复提交");
            }
            // 已被拒绝：引导使用重新提交，而不是新建（同样按阶段区分，仅同阶段拒绝记录才拦截）
            long rejected = baseMapper.selectCount(new LambdaQueryWrapper<EmployeeProjectParticipation>()
                    .eq(EmployeeProjectParticipation::getEmployeeId, employeeId)
                    .eq(EmployeeProjectParticipation::getPeriodId, periodId)
                    .eq(EmployeeProjectParticipation::getProjectCode, item.getProjectCode())
                    .eq(EmployeeProjectParticipation::getProjectStage, item.getProjectStage())
                    .eq(EmployeeProjectParticipation::getStatus, "REJECTED"));
            if (rejected > 0) {
                throw new BusinessException(409,
                        "项目" + item.getProjectCode() + " 已被拒绝，请使用「重新提交」功能");
            }

            EmployeeProjectParticipation participation = new EmployeeProjectParticipation();
            participation.setEmployeeId(employeeId);
            participation.setPeriodId(periodId);
            participation.setProjectCode(item.getProjectCode());
            participation.setProjectStage(item.getProjectStage());
            participation.setParticipationRate(item.getParticipationRate());
            participation.setStatus("PENDING");
            participation.setCreatedAt(now);
            participation.setUpdatedAt(now);
            // 并发重复提交时 DB 唯一约束 uk_part_emp_period_project 兜底，转 409 而非 500
            try {
                baseMapper.insert(participation);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(409,
                        "项目" + item.getProjectCode() + " 已存在参与记录，请刷新后重试");
            }
            notifiedProjectCodes.add(item.getProjectCode());
        }

        // 通知参与项目的主PM审批
        notifyPrimaryPms(notifiedProjectCodes);

        // 重新查询返回本次创建的记录
        return baseMapper.selectList(new LambdaQueryWrapper<EmployeeProjectParticipation>()
                .eq(EmployeeProjectParticipation::getEmployeeId, employeeId)
                .eq(EmployeeProjectParticipation::getPeriodId, periodId)
                .eq(EmployeeProjectParticipation::getStatus, "PENDING"));
    }

    // 功能：PM审批项目参与——通过(APPROVED)或不通过(REJECTED)，可填写建议投入比重与审批意见
    // 事务边界：审批状态更新与考核任务增量生成在同一事务中(见下方 TODO)
    @Transactional
    public EmployeeProjectParticipation approve(Long id, Boolean approved, BigDecimal suggestedRate, String comment) {
        EmployeeProjectParticipation participation = baseMapper.selectById(id);
        if (participation == null) {
            throw new BusinessException(404, "参与记录不存在: " + id);
        }
        // 项目级权限校验：非 ADMIN 仅该项目的主 PM(project_role_code='PM' AND is_primary=true AND project_stage=参与记录阶段)可审批
        if (!"ADMIN".equals(getPrimaryRole())) {
            String currentEmployeeId = getCurrentEmployeeId();
            boolean isPrimaryPm = currentEmployeeId != null
                    && StringUtils.hasText(participation.getProjectCode())
                    && StringUtils.hasText(participation.getProjectStage())
                    && projectRoleAssignmentMapper.selectCount(new LambdaQueryWrapper<ProjectRoleAssignment>()
                            .eq(ProjectRoleAssignment::getEmployeeId, currentEmployeeId)
                            .eq(ProjectRoleAssignment::getProjectCode, participation.getProjectCode())
                            .eq(ProjectRoleAssignment::getProjectStage, participation.getProjectStage())
                            .eq(ProjectRoleAssignment::getProjectRoleCode, "PM")
                            .eq(ProjectRoleAssignment::getIsPrimary, true)
                            .eq(ProjectRoleAssignment::getDeleted, 0)) > 0;
            if (!isPrimaryPm) {
                throw new BusinessException(403, "仅该项目的主 PM 可审批参与记录");
            }
        }
        // 周期锁定：INIT/ONGOING 可审批（CALIBRATING/CONFIRMED/COMPLETED 均拒绝，校准期冻结）
        periodService.assertParticipatable(participation.getPeriodId(), "审批");
        if (!"PENDING".equals(participation.getStatus())) {
            throw new BusinessException(400, "该参与记录已处理，不可重复审批");
        }

        participation.setApprovedBy(getCurrentUsername());
        participation.setApprovedAt(LocalDateTime.now());
        // 建议投入比重：通过/不通过均可填（不通过时作为员工重新提交的参考），1-100% 校验
        if (suggestedRate != null) {
            if (suggestedRate.compareTo(ONE) < 0 || suggestedRate.compareTo(ONE_HUNDRED) > 0) {
                throw new BusinessException(400, "建议投入比重必须在1%-100%之间");
            }
            participation.setSuggestedRate(suggestedRate);
        }
        if (StringUtils.hasText(comment)) {
            participation.setApprovalComment(comment.trim());
        }
        if (Boolean.TRUE.equals(approved)) {
            participation.setStatus("APPROVED");
        } else {
            participation.setStatus("REJECTED");
        }
        participation.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(participation);

        // 功能：审批通过后增量生成该员工的考核任务（同事务，任务生成失败则审批一并回滚）
        if ("APPROVED".equals(participation.getStatus())) {
            taskGeneratorService.onParticipationApproved(participation);
        }

        return participation;
    }

    // 功能：重新提交被拒绝的参与申请——可更新投入比重(1-100%)，状态从 REJECTED 重置为 PENDING，清空审批信息
    @Transactional
    public EmployeeProjectParticipation resubmit(Long id, BigDecimal participationRate) {
        EmployeeProjectParticipation participation = baseMapper.selectById(id);
        if (participation == null) {
            throw new BusinessException(404, "参与记录不存在: " + id);
        }
        // 归属校验：非 ADMIN 只能重新提交本人的参与记录，防止越权重置他人记录
        if (!"ADMIN".equals(getPrimaryRole())) {
            String currentEmployeeId = getCurrentEmployeeId();
            if (currentEmployeeId == null || !currentEmployeeId.equals(participation.getEmployeeId())) {
                throw new BusinessException(403, "无权操作他人参与记录");
            }
        }
        // 周期锁定：INIT/ONGOING 可重新提交（CALIBRATING/CONFIRMED/COMPLETED 均拒绝，校准期冻结）
        periodService.assertParticipatable(participation.getPeriodId(), "重新提交");
        if (!"REJECTED".equals(participation.getStatus())) {
            throw new BusinessException(400, "只有已拒绝的参与记录才能重新提交");
        }
        // 投入比重更新：单条 1-100% 校验（不跨记录校验合计，跨记录校验见 Phase 2.1 增强）
        if (participationRate != null) {
            if (participationRate.compareTo(ONE) < 0 || participationRate.compareTo(ONE_HUNDRED) > 0) {
                throw new BusinessException(400, "投入比重必须在1%-100%之间");
            }
            participation.setParticipationRate(participationRate);
        }
        participation.setStatus("PENDING");
        participation.setApprovedBy(null);
        participation.setApprovedAt(null);
        participation.setSuggestedRate(null);
        participation.setApprovalComment(null);
        participation.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(participation);

        // 重新提交后同样通知该项目主PM审批
        String projectCode = participation.getProjectCode();
        if (StringUtils.hasText(projectCode)) {
            notifyPrimaryPms(Set.of(projectCode));
        }

        return participation;
    }

    // 功能：删除参与记录——逻辑删除（@TableLogic），记录不存在时抛 404
    public void delete(Long id) {
        EmployeeProjectParticipation participation = baseMapper.selectById(id);
        if (participation == null) {
            throw new BusinessException(404, "参与记录不存在: " + id);
        }
        baseMapper.deleteById(id); // @TableLogic 逻辑删除
    }

    // 功能：通知参与项目的主PM——查 project_role_code='PM' AND is_primary=true 的分配记录，反查 user_id 后批量发送站内通知
    private void notifyPrimaryPms(Set<String> projectCodes) {
        if (projectCodes == null || projectCodes.isEmpty()) {
            return;
        }
        List<ProjectRoleAssignment> assignments = projectRoleAssignmentMapper.selectList(
                new LambdaQueryWrapper<ProjectRoleAssignment>()
                        .in(ProjectRoleAssignment::getProjectCode, projectCodes)
                        .eq(ProjectRoleAssignment::getProjectRoleCode, "PM")
                        .eq(ProjectRoleAssignment::getIsPrimary, true)
                        .eq(ProjectRoleAssignment::getDeleted, 0));
        if (assignments == null || assignments.isEmpty()) {
            return;
        }
        Set<String> employeeIds = assignments.stream()
                .map(ProjectRoleAssignment::getEmployeeId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (employeeIds.isEmpty()) {
            return;
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getEmployeeId, employeeIds));
        if (users == null || users.isEmpty()) {
            return;
        }
        List<Notification> notifications = users.stream().map(user -> {
            Notification n = new Notification();
            n.setRecipientId(user.getUserId());
            n.setTitle("新的项目参与申请待审批");
            n.setContent("有员工提交了项目参与申请，请前往项目参与页面进行审批。");
            n.setType("PARTICIPATION_PENDING");
            n.setTargetUrl("/participation");
            n.setIsRead(false);
            return n;
        }).toList();
        notificationService.notifyBatch(notifications);
    }

    // 功能：从Spring Security上下文获取当前登录用户名，未认证时返回"system"
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "system";
        }
        return auth.getName();
    }

    // 功能：根据登录用户名反查员工工号——参与记录按 employeeId 关联
    public String getCurrentEmployeeId() {
        String username = getCurrentUsername();
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        return user != null ? user.getEmployeeId() : null;
    }
}
