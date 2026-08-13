// 模块用途：考核任务业务逻辑——查询任务列表、开始评分、取消任务，状态转换委托状态机
// 依赖文件：TaskMapper.java, AssessmentTask.java, TaskStateMachine.java, BaseService.java
// 修改注意：状态转换必须经过 TaskStateMachine.transition，禁止直接 setStatus
package com.jifeng.assessment.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TaskService extends BaseService<TaskMapper, AssessmentTask> {

    private final TaskStateMachine taskStateMachine;

    // 功能：分页查询考核任务——支持按周期/状态/项目编码/考核人/被考核人筛选
    public PageResult<AssessmentTask> listTasks(PageQuery query, String periodId, String status,
                                                String projectCode, String assessorId, String assesseeId) {
        LambdaQueryWrapper<AssessmentTask> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(periodId)) {
            wrapper.eq(AssessmentTask::getPeriodId, periodId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(AssessmentTask::getStatus, status);
        }
        if (StringUtils.hasText(projectCode)) {
            wrapper.eq(AssessmentTask::getProjectCode, projectCode);
        }
        if (StringUtils.hasText(assessorId)) {
            wrapper.eq(AssessmentTask::getAssessorId, assessorId);
        }
        if (StringUtils.hasText(assesseeId)) {
            wrapper.eq(AssessmentTask::getAssesseeId, assesseeId);
        }
        wrapper.orderByAsc(AssessmentTask::getId);
        return selectPage(query, wrapper);
    }

    // 功能：开始评分——PENDING → IN_PROGRESS，经状态机校验
    @Transactional
    public AssessmentTask start(Long taskId) {
        AssessmentTask task = baseMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "考核任务不存在: " + taskId);
        }
        TaskStatus target = taskStateMachine.transition(
                TaskStatus.valueOf(task.getStatus()), TaskAction.START,
                task.getReturnCount(), task.getMaxReturns());
        task.setStatus(target.name());
        task.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(task);
        return task;
    }

    // 功能：取消任务——PENDING/IN_PROGRESS/RETURNED → CANCELED，经状态机校验
    @Transactional
    public AssessmentTask cancel(Long taskId) {
        AssessmentTask task = baseMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "考核任务不存在: " + taskId);
        }
        TaskStatus target = taskStateMachine.transition(
                TaskStatus.valueOf(task.getStatus()), TaskAction.CANCEL,
                task.getReturnCount(), task.getMaxReturns());
        task.setStatus(target.name());
        task.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(task);
        return task;
    }
}
