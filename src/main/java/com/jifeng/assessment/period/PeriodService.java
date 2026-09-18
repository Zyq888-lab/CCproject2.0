// 模块用途：考核周期业务逻辑——CRUD、活跃周期唯一约束、编辑校验、关闭周期
// 依赖文件：PeriodMapper.java, AssessmentPeriod.java
// 修改注意：同一时间只能有一个非COMPLETED周期，创建时自动生成periodId
package com.jifeng.assessment.period;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.result.ResultService;
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
    private final ResultService resultService;

    private static final String COMPLETED = "COMPLETED";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String CALIBRATING = "CALIBRATING";
    private static final String ONGOING = "ONGOING";
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

    // 功能：进入校准——ONGOING→CALIBRATING 原子翻转，打分结束进入校准确认阶段
    @Transactional
    public AssessmentPeriod enterCalibration(String periodId) {
        requirePeriod(periodId);
        int updated = periodMapper.updateStatus(periodId, ONGOING, CALIBRATING);
        if (updated == 0) {
            throw new BusinessException(400, "仅进行中的周期可进入校准");
        }
        // 进入校准即生成结果行：仅聚合 SUBMITTED 任务，未提交员工跳空不生成（完整性软门不阻断）
        resultService.generateResults(periodId);
        return periodMapper.selectById(periodId);
    }

    // 功能：总裁确认——CALIBRATING→CONFIRMED，单条 UPDATE WHERE status='CALIBRATING' 原子翻转，
    //   避免并发下重复确认/越级确认（read-modify-write 会漏检）
    @Transactional
    public AssessmentPeriod confirmPeriod(String periodId) {
        requirePeriod(periodId);
        int updated = periodMapper.updateStatus(periodId, CALIBRATING, CONFIRMED);
        if (updated == 0) {
            throw new BusinessException(400, "仅校准中的周期可确认");
        }
        // 确认时重生成一次结果（幂等 upsert，刷新 original、保留 adjusted），未提交员工仍跳空
        resultService.generateResults(periodId);
        return periodMapper.selectById(periodId);
    }

    // 功能：关闭考核周期——仅 CONFIRMED→COMPLETED，需先完成总裁确认（防旁路）
    @Transactional
    public AssessmentPeriod closePeriod(String periodId) {
        AssessmentPeriod period = requirePeriod(periodId);
        if (COMPLETED.equals(period.getStatus())) {
            throw new BusinessException(400, "该考核周期已关闭，无需重复操作");
        }
        if (!CONFIRMED.equals(period.getStatus())) {
            throw new BusinessException(400, "仅已确认的周期可关闭，请先完成总裁确认");
        }
        int updated = periodMapper.updateStatus(periodId, CONFIRMED, COMPLETED);
        if (updated == 0) {
            throw new BusinessException(409, "周期状态已变更，请刷新后重试");
        }
        return periodMapper.selectById(periodId);
    }

    // 功能：强制关闭（abort）——任意非COMPLETED状态直接置为COMPLETED，作为异常周期的逃生出口
    @Transactional
    public AssessmentPeriod abortPeriod(String periodId) {
        requirePeriod(periodId);
        int updated = periodMapper.forceComplete(periodId);
        if (updated == 0) {
            throw new BusinessException(400, "该考核周期已关闭，无需重复操作");
        }
        return periodMapper.selectById(periodId);
    }

    // 功能：结果可见性——仅 CONFIRMED（已确认待关闭）或 COMPLETED（已关闭）时员工可查看最终结果
    public boolean isResultVisible(String periodId) {
        if (!StringUtils.hasText(periodId)) {
            return false;
        }
        AssessmentPeriod period = periodMapper.selectById(periodId);
        return period != null && (CONFIRMED.equals(period.getStatus()) || COMPLETED.equals(period.getStatus()));
    }

    // 功能：加载周期，不存在抛404
    private AssessmentPeriod requirePeriod(String periodId) {
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }
        return period;
    }

    // 功能：校验周期未关闭——周期已 COMPLETED 时拒绝所有写操作（评分/审批/提交参与/上传凭证等）
    // 返回400而非403：这是业务状态锁，不是权限问题
    public void assertNotCompleted(String periodId, String action) {
        if (!StringUtils.hasText(periodId)) {
            return; // 无周期信息的记录（历史遗留）不拦截
        }
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period != null && COMPLETED.equals(period.getStatus())) {
            throw new BusinessException(400, "考核周期已关闭，不可再" + action);
        }
    }

    // 功能：校验周期已发起且未关闭——评分/开始评分等操作要求周期必须为 ONGOING；
    //   COMPLETED 抛「已关闭」，INIT/CALIBRATING 等非 ONGOING 状态抛「尚未发起」
    public void assertOngoing(String periodId, String action) {
        if (!StringUtils.hasText(periodId)) {
            return; // 无周期信息的记录（历史遗留）不拦截
        }
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            return;
        }
        if (COMPLETED.equals(period.getStatus())) {
            throw new BusinessException(400, "考核周期已关闭，不可再" + action);
        }
        if (!ONGOING.equals(period.getStatus())) {
            throw new BusinessException(400, "考核尚未发起，不可" + action);
        }
    }

    // 功能：校验周期可填写/审批项目参与——INIT 与 ONGOING 均允许（INIT 期参与由 launch 统一生成任务）；
    //   COMPLETED 抛「已关闭」，CALIBRATING/CONFIRMED 抛「已进入校准/确认」，冻结参与写操作
    public void assertParticipatable(String periodId, String action) {
        if (!StringUtils.hasText(periodId)) {
            return; // 无周期信息的记录（历史遗留）不拦截
        }
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            return;
        }
        String status = period.getStatus();
        if (COMPLETED.equals(status)) {
            throw new BusinessException(400, "考核周期已关闭，不可再" + action);
        }
        if (CALIBRATING.equals(status) || CONFIRMED.equals(status)) {
            throw new BusinessException(400, "考核已进入校准/确认，不可再" + action);
        }
        // INIT / ONGOING 放行
    }
}
