// 模块用途：考核周期业务逻辑——CRUD、活跃周期唯一约束、编辑校验、关闭周期
// 依赖文件：PeriodMapper.java, AssessmentPeriod.java
// 修改注意：同一时间只能有一个非COMPLETED周期，创建时自动生成periodId
package com.jifeng.assessment.period;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PeriodService {

    private final PeriodMapper periodMapper;

    private static final String COMPLETED = "COMPLETED";
    private static final String INIT = "INIT";

    // 功能：查询考核周期列表，支持按status筛选
    public List<AssessmentPeriod> listPeriods(String status) {
        LambdaQueryWrapper<AssessmentPeriod> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(AssessmentPeriod::getStatus, status);
        }
        wrapper.orderByDesc(AssessmentPeriod::getCreatedAt);
        return periodMapper.selectList(wrapper);
    }

    // 功能：创建考核周期——自动生成periodId，校验无活跃周期
    @Transactional
    public AssessmentPeriod createPeriod(AssessmentPeriod period) {
        if (period.getStartDate() != null && period.getEndDate() != null
                && period.getStartDate().isAfter(period.getEndDate())) {
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        }
        // 活跃周期唯一约束：不能存在非COMPLETED的周期
        long activeCount = periodMapper.selectCount(
                new LambdaQueryWrapper<AssessmentPeriod>().ne(AssessmentPeriod::getStatus, COMPLETED));
        if (activeCount > 0) {
            throw new BusinessException(409, "当前已有未关闭的考核周期，请先关闭后再创建新周期");
        }
        period.setPeriodId(UUID.randomUUID().toString().replace("-", ""));
        period.setStatus(INIT);
        periodMapper.insert(period);
        return periodMapper.selectById(period.getPeriodId());
    }

    // 功能：编辑考核周期——仅INIT状态可修改名称和起止日期
    @Transactional
    public AssessmentPeriod updatePeriod(String periodId, AssessmentPeriod update) {
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }
        if (!INIT.equals(period.getStatus())) {
            throw new BusinessException(400, "仅未开始的考核周期可编辑");
        }
        if (update.getStartDate() != null && update.getEndDate() != null
                && update.getStartDate().isAfter(update.getEndDate())) {
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        }
        if (update.getPeriodName() != null) {
            period.setPeriodName(update.getPeriodName());
        }
        if (update.getStartDate() != null) {
            period.setStartDate(update.getStartDate());
        }
        if (update.getEndDate() != null) {
            period.setEndDate(update.getEndDate());
        }
        period.setUpdatedAt(LocalDateTime.now());
        periodMapper.updateById(period);
        return periodMapper.selectById(periodId);
    }

    // 功能：关闭考核周期——状态设为COMPLETED
    @Transactional
    public AssessmentPeriod closePeriod(String periodId) {
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }
        if (COMPLETED.equals(period.getStatus())) {
            throw new BusinessException(400, "该考核周期已关闭，无需重复操作");
        }
        period.setStatus(COMPLETED);
        period.setUpdatedAt(LocalDateTime.now());
        periodMapper.updateById(period);
        return period;
    }
}
