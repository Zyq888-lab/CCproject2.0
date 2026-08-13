// 模块用途：项目参与业务逻辑——员工填写多项目参与(投入比重)、PM审批通过/不通过
// 依赖文件：ParticipationMapper.java, EmployeeProjectParticipation.java, BaseService.java, SysUserMapper.java
// 修改注意：投入比重总和必须=100%，单项≥1%；审批与考核任务生成在同一事务中(见 approve 方法 TODO)
package com.jifeng.assessment.participation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ParticipationService extends BaseService<ParticipationMapper, EmployeeProjectParticipation> {

    private final SysUserMapper sysUserMapper;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ONE = BigDecimal.ONE;

    // 功能：分页查询项目参与列表——支持按周期和状态筛选，员工默认只看自己的记录
    public PageResult<EmployeeProjectParticipation> listParticipations(PageQuery query, String periodId, String status, String employeeId) {
        LambdaQueryWrapper<EmployeeProjectParticipation> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(periodId)) {
            wrapper.eq(EmployeeProjectParticipation::getPeriodId, periodId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(EmployeeProjectParticipation::getStatus, status);
        }
        if (StringUtils.hasText(employeeId)) {
            wrapper.eq(EmployeeProjectParticipation::getEmployeeId, employeeId);
        }
        wrapper.orderByDesc(EmployeeProjectParticipation::getId);
        return selectPage(query, wrapper);
    }

    // 功能：员工填写项目参与——校验投入比重总和=100%、单项≥1%，逐条插入为 PENDING 状态
    @Transactional
    public List<EmployeeProjectParticipation> create(String employeeId, String periodId,
                                                     List<ProjectParticipationItem> items) {
        if (!StringUtils.hasText(employeeId)) {
            throw new BusinessException(400, "员工工号不能为空");
        }
        if (!StringUtils.hasText(periodId)) {
            throw new BusinessException(400, "考核周期不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw new BusinessException(400, "至少填写一个项目的参与记录");
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
        for (ProjectParticipationItem item : items) {
            EmployeeProjectParticipation participation = new EmployeeProjectParticipation();
            participation.setEmployeeId(employeeId);
            participation.setPeriodId(periodId);
            participation.setProjectCode(item.getProjectCode());
            participation.setProjectStage(item.getProjectStage());
            participation.setParticipationRate(item.getParticipationRate());
            participation.setStatus("PENDING");
            participation.setCreatedAt(now);
            participation.setUpdatedAt(now);
            baseMapper.insert(participation);
        }

        // 重新查询返回本次创建的记录
        return baseMapper.selectList(new LambdaQueryWrapper<EmployeeProjectParticipation>()
                .eq(EmployeeProjectParticipation::getEmployeeId, employeeId)
                .eq(EmployeeProjectParticipation::getPeriodId, periodId)
                .eq(EmployeeProjectParticipation::getStatus, "PENDING"));
    }

    // 功能：PM审批项目参与——通过(APPROVED)或不通过(REJECTED)，可填写建议投入比重
    // 事务边界：审批状态更新与考核任务增量生成在同一事务中(见下方 TODO)
    @Transactional
    public EmployeeProjectParticipation approve(Long id, Boolean approved, BigDecimal suggestedRate) {
        EmployeeProjectParticipation participation = baseMapper.selectById(id);
        if (participation == null) {
            throw new BusinessException(404, "参与记录不存在: " + id);
        }
        if (!"PENDING".equals(participation.getStatus())) {
            throw new BusinessException(400, "该参与记录已处理，不可重复审批");
        }

        participation.setApprovedBy(getCurrentUsername());
        participation.setApprovedAt(LocalDateTime.now());
        if (Boolean.TRUE.equals(approved)) {
            participation.setStatus("APPROVED");
            if (suggestedRate != null) {
                if (suggestedRate.compareTo(ONE) < 0 || suggestedRate.compareTo(ONE_HUNDRED) > 0) {
                    throw new BusinessException(400, "建议投入比重必须在1%-100%之间");
                }
                participation.setSuggestedRate(suggestedRate);
            }
        } else {
            participation.setStatus("REJECTED");
        }
        participation.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(participation);

        // TODO(T3): 审批通过后调用 TaskGeneratorService 增量生成该员工的考核任务。
        //   触发条件：participation.status == "APPROVED"
        //   事务边界：本方法 @Transactional 已覆盖，任务生成失败时审批一并回滚
        //   if ("APPROVED".equals(participation.getStatus())) {
        //       taskGeneratorService.onParticipationApproved(participation);
        //   }

        return participation;
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
