package com.jifeng.assessment.score;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.security.ProjectAccessService;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EvidenceAccessService {

    private final ScoreMapper scoreMapper;
    private final TaskMapper taskMapper;
    private final PeriodService periodService;
    private final ProjectAccessService projectAccessService;

    public void assertCanDownload(String filename) {
        AssessmentScore score = scoreMapper.selectOne(new LambdaQueryWrapper<AssessmentScore>()
                .eq(AssessmentScore::getEvidenceUrl, "/api/v1/evidence/" + filename));
        if (score == null) {
            throw new BusinessException(404, "凭证记录不存在");
        }
        AssessmentTask task = taskMapper.selectById(score.getTaskId());
        if (task == null) {
            throw new BusinessException(404, "凭证所属任务不存在");
        }
        if (projectAccessService.isAdmin()) {
            return;
        }
        String employeeId = projectAccessService.currentEmployeeId();
        if (employeeId == null) {
            throw new BusinessException(403, "无权查看该凭证");
        }
        if (employeeId.equals(task.getAssessorId())) {
            return;
        }
        if ("SUBMITTED".equals(task.getStatus()) && "PROJECT".equals(task.getTaskType())
                && (projectAccessService.isPrimaryRole(task.getProjectCode(), task.getProjectStage(), "PD")
                    || projectAccessService.isPrimaryRole(task.getProjectCode(), task.getProjectStage(), "PRESIDENT"))) {
            return;
        }
        if (employeeId.equals(task.getAssesseeId()) && periodService.isResultVisible(task.getPeriodId())) {
            return;
        }
        throw new BusinessException(403, "无权查看该凭证");
    }
}
