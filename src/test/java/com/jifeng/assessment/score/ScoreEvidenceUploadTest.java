package com.jifeng.assessment.score;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.task.TaskService;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScoreEvidenceUploadTest {
    @TempDir Path tempDir;
    @Mock TaskMapper taskMapper;
    @Mock TaskService taskService;
    @Mock ScoreMapper scoreMapper;
    @Mock ProjectKpiMapper projectKpiMapper;
    @Mock FuncKpiMapper funcKpiMapper;
    @Mock SysUserMapper sysUserMapper;
    @Mock PeriodService periodService;
    @InjectMocks ScoreService scoreService;

    private AssessmentScore score;
    private AssessmentTask task;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scoreService, "baseMapper", scoreMapper);
        ReflectionTestUtils.setField(scoreService, "uploadDir", tempDir.resolve("evidence").toString());
        score = new AssessmentScore();
        score.setId(10L);
        score.setTaskId(20L);
        task = new AssessmentTask();
        task.setId(20L);
        task.setPeriodId("PERIOD-1");
        task.setAssessorId("E001");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "assessor", null, List.of(new SimpleGrantedAuthority("ROLE_评估人"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void allowUpload() {
        when(scoreMapper.selectById(10L)).thenReturn(score);
        when(taskMapper.selectById(20L)).thenReturn(task);
        SysUser user = new SysUser();
        user.setEmployeeId("E001");
        when(sysUserMapper.selectOne(any())).thenReturn(user);
    }

    @Test
    void uploadPersistsBytesAndOnlyThenReturnsEvidenceUrl() throws IOException {
        allowUpload();
        MockMultipartFile file = new MockMultipartFile("file", "proof.png", "image/png", new byte[] {1, 2, 3});

        String url = scoreService.uploadEvidence(10L, file);

        assertTrue(url.matches("/api/v1/evidence/[0-9a-f]{32}\\.png"));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(
                tempDir.resolve("evidence").resolve(url.substring(url.lastIndexOf('/') + 1))));
        assertEquals(url, score.getEvidenceUrl());
        verify(scoreMapper).updateById(score);
    }

    @Test
    void uploadRejectsAnotherAssessorWithoutWritingFile() {
        allowUpload();
        task.setAssessorId("E002");

        BusinessException ex = assertThrows(BusinessException.class, () -> scoreService.uploadEvidence(10L,
                new MockMultipartFile("file", "proof.png", "image/png", new byte[] {1})));

        assertEquals(403, ex.getCode());
        assertFalse(Files.exists(tempDir.resolve("evidence")));
        verify(scoreMapper, never()).updateById(any());
    }

    @Test
    void uploadRejectsOversizeFileWithoutWritingFile() {
        allowUpload();
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(10L * 1024 * 1024 + 1);

        BusinessException ex = assertThrows(BusinessException.class, () -> scoreService.uploadEvidence(10L, file));

        assertEquals(400, ex.getCode());
        assertFalse(Files.exists(tempDir.resolve("evidence")));
        verify(scoreMapper, never()).updateById(any());
    }

    @Test
    void uploadDoesNotReturnUrlWhenFileWriteFails() throws IOException {
        allowUpload();
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("proof.png");
        doThrow(new IOException("disk full")).when(file).transferTo(any(Path.class));

        BusinessException ex = assertThrows(BusinessException.class, () -> scoreService.uploadEvidence(10L, file));

        assertEquals(500, ex.getCode());
        assertNull(score.getEvidenceUrl());
        verify(scoreMapper, never()).updateById(any());
    }

    @Test
    void uploadRejectsMissingScoreOrTask() {
        BusinessException missingScore = assertThrows(BusinessException.class,
                () -> scoreService.uploadEvidence(10L, new MockMultipartFile("file", new byte[] {1})));
        assertEquals(404, missingScore.getCode());

        when(scoreMapper.selectById(10L)).thenReturn(score);
        BusinessException missingTask = assertThrows(BusinessException.class,
                () -> scoreService.uploadEvidence(10L, new MockMultipartFile("file", new byte[] {1})));
        assertEquals(404, missingTask.getCode());
        verify(scoreMapper, never()).updateById(any());
    }

    @Test
    void uploadRejectsEmptyFile() {
        allowUpload();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> scoreService.uploadEvidence(10L, new MockMultipartFile("file", new byte[0])));

        assertEquals(400, ex.getCode());
        assertFalse(Files.exists(tempDir.resolve("evidence")));
        verify(scoreMapper, never()).updateById(any());
    }

    @Test
    void uploadRejectsClosedPeriod() {
        allowUpload();
        doThrow(new BusinessException(400, "考核周期已关闭"))
                .when(periodService).assertOngoing("PERIOD-1", "上传凭证");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> scoreService.uploadEvidence(10L, new MockMultipartFile("file", new byte[] {1})));

        assertEquals(400, ex.getCode());
        assertFalse(Files.exists(tempDir.resolve("evidence")));
        verify(scoreMapper, never()).updateById(any());
    }
}
