// 模块用途：ScoreService 单元测试——覆盖提交评分的指标完整性/得分范围/kpiType一致性/乐观锁冲突
// 依赖文件：ScoreService.java, ScoreMapper.java, TaskMapper.java, TaskStateMachine.java
// 修改注意：Mockito 模拟全部依赖，不依赖真实数据库；TaskStateMachine 用真实实例(纯逻辑)
package com.jifeng.assessment.score;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.task.TaskStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScoreServiceTest {

    @Mock private TaskMapper taskMapper;
    @Mock private ScoreMapper scoreMapper;
    @Mock private ProjectKpiMapper projectKpiMapper;
    @Mock private FuncKpiMapper funcKpiMapper;

    @InjectMocks
    private ScoreService scoreService;

    private AssessmentTask inProgressTask;

    @BeforeEach
    void setUp() {
        inProgressTask = new AssessmentTask();
        inProgressTask.setId(1L);
        inProgressTask.setTaskType("PROJECT");
        inProgressTask.setStatus("IN_PROGRESS");
        inProgressTask.setReturnCount(0);
        inProgressTask.setMaxReturns(3);
        inProgressTask.setVersion(0L);

        // 注入真实状态机（纯逻辑，无依赖）
        ReflectionTestUtils.setField(scoreService, "taskStateMachine", new TaskStateMachine());
        // baseMapper 由 BaseService 持有，注入 mock 的 ScoreMapper
        ReflectionTestUtils.setField(scoreService, "baseMapper", scoreMapper);
    }

    // 辅助方法：构建一个合法的评分项
    private ScoreService.ScoreItem validItem() {
        ScoreService.ScoreItem item = new ScoreService.ScoreItem();
        item.setKpiConfigId(100L);
        item.setKpiType("PROJECT");
        item.setScore(new BigDecimal("4.5"));
        return item;
    }

    // ========================================
    // 1. 提交评分时指标不完整（空列表）→ BusinessException
    // ========================================
    @Test
    void submitShouldRejectEmptyItems() {
        when(taskMapper.selectById(1L)).thenReturn(inProgressTask);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> scoreService.submit(1L, List.of()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("未评分"));
        verify(scoreMapper, never()).insert(any());
    }

    // ========================================
    // 2. 得分超出 1.0-5.0 范围 → BusinessException
    // ========================================
    @Test
    void submitShouldRejectScoreOutOfRange() {
        when(taskMapper.selectById(1L)).thenReturn(inProgressTask);

        ScoreService.ScoreItem item = validItem();
        item.setScore(new BigDecimal("5.5"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> scoreService.submit(1L, List.of(item)));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("1-5"));
        verify(scoreMapper, never()).insert(any());
    }

    // ========================================
    // 3. kpiType 与 taskType 不一致 → BusinessException
    // ========================================
    @Test
    void submitShouldRejectMismatchedKpiType() {
        when(taskMapper.selectById(1L)).thenReturn(inProgressTask); // taskType=PROJECT

        ScoreService.ScoreItem item = validItem();
        item.setKpiType("FUNCTIONAL"); // 与 PROJECT 不一致

        BusinessException ex = assertThrows(BusinessException.class,
                () -> scoreService.submit(1L, List.of(item)));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不一致"));
        verify(scoreMapper, never()).insert(any());
    }

    // ========================================
    // 4. 提交时 task.version 冲突 → 返回 409
    // ========================================
    @Test
    void submitShouldReturn409OnVersionConflict() {
        when(taskMapper.selectById(1L)).thenReturn(inProgressTask);
        when(projectKpiMapper.selectById(100L)).thenReturn(new com.jifeng.assessment.kpi.ProjectKpiConfig());
        // upsert 时查重返回 null（无已有草稿）
        when(scoreMapper.selectOne(any())).thenReturn(null);
        // 乐观锁更新：影响行数为 0 → 409
        when(taskMapper.updateById(any(AssessmentTask.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> scoreService.submit(1L, List.of(validItem())));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被他人修改"));
    }
}
