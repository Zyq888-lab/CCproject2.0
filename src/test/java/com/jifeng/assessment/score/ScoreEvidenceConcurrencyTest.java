package com.jifeng.assessment.score;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// 非事务测试：真实服务各自提交事务，验证上传与提交在同一任务行锁下串行执行。
@SpringBootTest
@ActiveProfiles("test")
class ScoreEvidenceConcurrencyTest {
    private static final String PERIOD_ID = "EVIDENCE_CONCURRENT";
    private static final String EMPLOYEE_ID = "EMP_EVID_CONC";
    private static final String POSITION = "凭证并发测试岗";

    @Autowired ScoreService service;
    @Autowired ScoreMapper scoreMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired PeriodMapper periodMapper;
    @Autowired EmployeeMapper employeeMapper;
    @Autowired FuncKpiMapper kpiMapper;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path uploadDirectory;
    private String originalDirectory;

    @BeforeEach
    void configureUploadDirectory() {
        originalDirectory = (String) ReflectionTestUtils.getField(service, "uploadDir");
        ReflectionTestUtils.setField(service, "uploadDir", uploadDirectory.toString());
    }

    @AfterEach
    void cleanup() {
        ReflectionTestUtils.setField(service, "uploadDir", originalDirectory);
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM assessment_score WHERE task_id IN (SELECT id FROM assessment_task WHERE period_id = ?)", PERIOD_ID);
        jdbc.update("DELETE FROM assessment_task WHERE period_id = ?", PERIOD_ID);
        jdbc.update("DELETE FROM assessment_period WHERE period_id = ?", PERIOD_ID);
        jdbc.update("DELETE FROM func_kpi_config WHERE position = ?", POSITION);
        jdbc.update("DELETE FROM employee WHERE employee_id = ?", EMPLOYEE_ID);
    }

    @Test
    void concurrentSubmissionWaitsForUploadAndPreservesEvidence() throws Exception {
        Employee employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        employee.setName("凭证并发测试");
        employee.setEmail("evidence-concurrent@test.invalid");
        employee.setCategory("管理类");
        employee.setPosition(POSITION);
        employee.setOrgName("测试部");
        employee.setStatus("ACTIVE");
        employeeMapper.insert(employee);

        AssessmentPeriod period = new AssessmentPeriod();
        period.setPeriodId(PERIOD_ID);
        period.setPeriodName("凭证并发测试周期");
        period.setStartDate(LocalDate.of(2026, 1, 1));
        period.setEndDate(LocalDate.of(2026, 12, 31));
        period.setStatus("ONGOING");
        periodMapper.insert(period);

        FuncKpiConfig kpi = new FuncKpiConfig();
        kpi.setCategory("管理类");
        kpi.setPosition(POSITION);
        kpi.setKpiName("并发测试指标");
        kpi.setWeight(BigDecimal.ONE);
        kpi.setIsActive(true);
        kpiMapper.insert(kpi);

        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(PERIOD_ID);
        task.setAssessorId(EMPLOYEE_ID);
        task.setAssesseeId(EMPLOYEE_ID);
        task.setTaskType("FUNCTIONAL");
        task.setStatus("IN_PROGRESS");
        taskMapper.insert(task);
        AssessmentScore score = new AssessmentScore();
        score.setTaskId(task.getId());
        score.setKpiConfigId(kpi.getId());
        score.setKpiType("FUNCTIONAL");
        score.setStatus("DRAFT");
        scoreMapper.insert(score);

        CountDownLatch writingFile = new CountDownLatch(1);
        CountDownLatch finishUpload = new CountDownLatch(1);
        CountDownLatch submitting = new CountDownLatch(1);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(3L);
        when(file.getOriginalFilename()).thenReturn("evidence.pdf");
        doAnswer(invocation -> {
            writingFile.countDown();
            assertTrue(finishUpload.await(10, TimeUnit.SECONDS));
            Files.write(invocation.getArgument(0, Path.class), new byte[]{1, 2, 3});
            return null;
        }).when(file).transferTo(any(Path.class));

        ScoreService.ScoreItem item = new ScoreService.ScoreItem();
        item.setKpiConfigId(kpi.getId());
        item.setKpiType("FUNCTIONAL");
        item.setScore(new BigDecimal("4.0"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> upload = pool.submit(() -> {
                authenticateAdmin();
                try { return service.uploadEvidence(score.getId(), file); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(writingFile.await(10, TimeUnit.SECONDS));
            Future<AssessmentTask> submission = pool.submit(() -> {
                authenticateAdmin();
                submitting.countDown();
                try { return service.submit(task.getId(), List.of(item)); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(submitting.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> submission.get(300, TimeUnit.MILLISECONDS),
                    "上传持有任务锁时，提交必须等待");
            finishUpload.countDown();
            String evidenceUrl = upload.get(10, TimeUnit.SECONDS);
            assertEquals("SUBMITTED", submission.get(10, TimeUnit.SECONDS).getStatus());
            AssessmentScore persisted = scoreMapper.selectById(score.getId());
            assertEquals(evidenceUrl, persisted.getEvidenceUrl());
            assertEquals("SUBMITTED", persisted.getStatus());
            assertTrue(Files.isRegularFile(uploadDirectory.resolve(evidenceUrl.substring(evidenceUrl.lastIndexOf('/') + 1))));

            authenticateAdmin();
            BusinessException rejected = assertThrows(BusinessException.class, () -> service.uploadEvidence(
                    score.getId(), new MockMultipartFile("file", "replacement.pdf", "application/pdf", new byte[]{9})));
            assertEquals(400, rejected.getCode());
            assertEquals(evidenceUrl, scoreMapper.selectById(score.getId()).getEvidenceUrl());
        } finally {
            finishUpload.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
