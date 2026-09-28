package com.jifeng.assessment.score;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.security.ProjectAccessService;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvidenceAccessServiceTest {
    @Mock ScoreMapper scoreMapper;
    @Mock TaskMapper taskMapper;
    @Mock PeriodService periodService;
    @Mock ProjectAccessService projectAccessService;
    @InjectMocks EvidenceAccessService accessService;

    private AssessmentTask task;

    @BeforeEach
    void setUp() {
        AssessmentScore score = new AssessmentScore();
        score.setTaskId(20L);
        when(scoreMapper.selectOne(any())).thenReturn(score);
        task = new AssessmentTask();
        task.setId(20L);
        task.setPeriodId("PERIOD-1");
        task.setAssessorId("ASSESSOR");
        task.setAssesseeId("SUBJECT");
        task.setProjectCode("P001");
        task.setProjectStage("P2");
        task.setTaskType("PROJECT");
        task.setStatus("SUBMITTED");
        when(taskMapper.selectById(20L)).thenReturn(task);
    }

    @Test
    void unrelatedAuthenticatedUserCannotDownloadRegisteredEvidence() {
        when(projectAccessService.currentEmployeeId()).thenReturn("STRANGER");
        assertEquals(403, assertThrows(BusinessException.class,
                () -> accessService.assertCanDownload("proof.pdf")).getCode());
    }

    @Test
    void assessorAndOwnedPdCanDownload() {
        when(projectAccessService.currentEmployeeId()).thenReturn("ASSESSOR", "PD_OWNER");
        when(projectAccessService.isPrimaryRole("P001", "P2", "PD")).thenReturn(true);
        assertDoesNotThrow(() -> accessService.assertCanDownload("proof.pdf"));
        assertDoesNotThrow(() -> accessService.assertCanDownload("proof.pdf"));
    }

    @Test
    void subjectCanDownloadOnlyAfterResultsPublished() {
        when(projectAccessService.currentEmployeeId()).thenReturn("SUBJECT");
        when(periodService.isResultVisible("PERIOD-1")).thenReturn(false, true);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> accessService.assertCanDownload("proof.pdf")).getCode());
        assertDoesNotThrow(() -> accessService.assertCanDownload("proof.pdf"));
    }

    @Test
    void ownedPresidentCanDownloadSubmittedProjectEvidence() {
        when(projectAccessService.currentEmployeeId()).thenReturn("PRESIDENT_OWNER");
        when(projectAccessService.isPrimaryRole("P001", "P2", "PD")).thenReturn(false);
        when(projectAccessService.isPrimaryRole("P001", "P2", "PRESIDENT")).thenReturn(true);
        assertDoesNotThrow(() -> accessService.assertCanDownload("proof.pdf"));
    }

    @Test
    void projectPdCannotDownloadFunctionalEvidence() {
        task.setTaskType("FUNCTIONAL");
        task.setProjectCode(null);
        task.setProjectStage(null);
        when(projectAccessService.currentEmployeeId()).thenReturn("PD_OWNER");
        assertEquals(403, assertThrows(BusinessException.class,
                () -> accessService.assertCanDownload("proof.pdf")).getCode());
    }
}
