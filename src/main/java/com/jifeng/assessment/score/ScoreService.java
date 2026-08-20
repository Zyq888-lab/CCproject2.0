// 模块用途：考核打分业务逻辑——提交评分、暂存草稿、凭证上传
// 依赖文件：ScoreMapper.java, AssessmentScore.java, TaskMapper.java, TaskStateMachine.java, 各 KPI Mapper, BaseService.java
// 修改注意：提交评分校验指标完整性/得分范围/kpiType一致性/task乐观锁；草稿不改变task状态
package com.jifeng.assessment.score;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiConfig;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.period.PeriodService;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.TaskAction;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.task.TaskStateMachine;
import com.jifeng.assessment.task.TaskStatus;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScoreService extends BaseService<ScoreMapper, AssessmentScore> {

    private final TaskMapper taskMapper;
    private final TaskStateMachine taskStateMachine;
    private final ProjectKpiMapper projectKpiMapper;
    private final FuncKpiMapper funcKpiMapper;
    private final SysUserMapper sysUserMapper;
    private final PeriodService periodService;

    private static final BigDecimal MIN_SCORE = new BigDecimal("1.0");
    private static final BigDecimal MAX_SCORE = new BigDecimal("5.0");
    private static final long MAX_EVIDENCE_SIZE = 10L * 1024 * 1024; // 10MB

    // 功能：提交评分——校验指标完整性、得分范围、kpiType一致性、task乐观锁，任务状态→SUBMITTED
    @Transactional
    public AssessmentTask submit(Long taskId, List<ScoreItem> items) {
        AssessmentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "考核任务不存在: " + taskId);
        }
        // 权限校验：当前登录用户必须是该任务的考核人（ADMIN 豁免），否则 403
        assertAssessor(task);
        // 周期锁定：考核尚未发起或已关闭时拒绝评分
        periodService.assertOngoing(task.getPeriodId(), "评分");
        if (!"IN_PROGRESS".equals(task.getStatus())) {
            throw new BusinessException(400, "仅评分中的任务可提交");
        }

        // 指标完整性校验：至少提交一个评分
        if (items == null || items.isEmpty()) {
            throw new BusinessException(400, "存在未评分的指标");
        }

        // 逐项校验得分范围、kpiType 一致性、KPI 存在性，并持久化评分
        LocalDateTime now = LocalDateTime.now();
        for (ScoreItem item : items) {
            // 得分范围校验：1.0 - 5.0
            if (item.getScore() == null
                    || item.getScore().compareTo(MIN_SCORE) < 0
                    || item.getScore().compareTo(MAX_SCORE) > 0) {
                throw new BusinessException(400, "得分必须在 1-5 分之间");
            }
            // kpiType 与 task.taskType 一致性校验
            if (!expectedKpiType(task).equals(item.getKpiType())) {
                throw new BusinessException(400,
                        "指标类型 " + item.getKpiType() + " 与任务类型 " + task.getTaskType() + " 不一致");
            }
            // KPI 存在性校验（多态引用）
            validateKpiExists(item.getKpiType(), item.getKpiConfigId());

            // upsert：草稿已存在则更新为 SUBMITTED，否则插入（与 saveDraft 保持一致，避免唯一约束冲突）
            AssessmentScore existing = baseMapper.selectOne(new LambdaQueryWrapper<AssessmentScore>()
                    .eq(AssessmentScore::getTaskId, taskId)
                    .eq(AssessmentScore::getKpiConfigId, item.getKpiConfigId())
                    .eq(AssessmentScore::getKpiType, item.getKpiType()));
            AssessmentScore score = existing != null ? existing : new AssessmentScore();
            score.setTaskId(taskId);
            score.setKpiConfigId(item.getKpiConfigId());
            score.setKpiType(item.getKpiType());
            score.setScore(item.getScore());
            score.setEvidenceUrl(item.getEvidenceUrl());
            score.setStatus("SUBMITTED");
            score.setUpdatedAt(now);
            if (existing != null) {
                // 乐观锁更新评分行：version 冲突(返回0)时抛 409，避免并发覆盖被静默吞掉
                int updated = baseMapper.updateById(score);
                if (updated == 0) {
                    throw new BusinessException(409, "评分数据已被他人修改，请刷新后重试");
                }
            } else {
                score.setCreatedAt(now);
                // 并发插入同一指标评分时 DB 唯一约束 uk_score_kpi 兜底，转 409 而非 500
                try {
                    baseMapper.insert(score);
                } catch (DuplicateKeyException e) {
                    throw new BusinessException(409, "该指标评分已存在，请刷新后重试");
                }
            }
        }

        // 乐观锁更新任务状态：IN_PROGRESS → SUBMITTED，version 冲突抛 409
        TaskStatus target = taskStateMachine.transition(
                TaskStatus.valueOf(task.getStatus()), TaskAction.SUBMIT,
                task.getReturnCount(), task.getMaxReturns());
        task.setStatus(target.name());
        task.setUpdatedAt(now);
        updateTaskWithOptimisticLock(task);
        return task;
    }

    // 功能：暂存草稿——可只填部分指标，不改变 task 状态
    @Transactional
    public void saveDraft(Long taskId, List<ScoreItem> items) {
        AssessmentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "考核任务不存在: " + taskId);
        }
        // 权限校验：当前登录用户必须是该任务的考核人（ADMIN 豁免），否则 403
        assertAssessor(task);
        // 周期锁定：考核尚未发起或已关闭时拒绝保存草稿
        periodService.assertOngoing(task.getPeriodId(), "保存评分草稿");

        LocalDateTime now = LocalDateTime.now();
        for (ScoreItem item : items) {
            // 草稿也校验得分范围和 kpiType，但不要求完整
            if (item.getScore() != null
                    && (item.getScore().compareTo(MIN_SCORE) < 0
                        || item.getScore().compareTo(MAX_SCORE) > 0)) {
                throw new BusinessException(400, "得分必须在 1-5 分之间");
            }
            if (!expectedKpiType(task).equals(item.getKpiType())) {
                throw new BusinessException(400,
                        "指标类型 " + item.getKpiType() + " 与任务类型 " + task.getTaskType() + " 不一致");
            }
            validateKpiExists(item.getKpiType(), item.getKpiConfigId());

            // 幂等：同一任务+同一KPI指标已存在草稿则覆盖，否则插入
            AssessmentScore existing = baseMapper.selectOne(new LambdaQueryWrapper<AssessmentScore>()
                    .eq(AssessmentScore::getTaskId, taskId)
                    .eq(AssessmentScore::getKpiConfigId, item.getKpiConfigId())
                    .eq(AssessmentScore::getKpiType, item.getKpiType()));
            AssessmentScore score = existing != null ? existing : new AssessmentScore();
            score.setTaskId(taskId);
            score.setKpiConfigId(item.getKpiConfigId());
            score.setKpiType(item.getKpiType());
            score.setScore(item.getScore());
            score.setEvidenceUrl(item.getEvidenceUrl());
            score.setStatus("DRAFT");
            score.setUpdatedAt(now);
            if (existing != null) {
                // 乐观锁更新评分行：version 冲突(返回0)时抛 409
                int updated = baseMapper.updateById(score);
                if (updated == 0) {
                    throw new BusinessException(409, "评分数据已被他人修改，请刷新后重试");
                }
            } else {
                score.setCreatedAt(now);
                // 并发插入同一指标评分时 DB 唯一约束 uk_score_kpi 兜底，转 409 而非 500
                try {
                    baseMapper.insert(score);
                } catch (DuplicateKeyException e) {
                    throw new BusinessException(409, "该指标评分已存在，请刷新后重试");
                }
            }
        }
        // 不改变 task 状态
    }

    // 功能：凭证上传——校验文件大小≤10MB，返回访问 URL
    @Transactional
    public String uploadEvidence(Long scoreId, MultipartFile file) {
        AssessmentScore score = baseMapper.selectById(scoreId);
        if (score == null) {
            throw new BusinessException(404, "评分记录不存在: " + scoreId);
        }
        // 权限校验：当前登录用户必须是该评分所属任务的考核人（ADMIN 豁免），否则 403
        AssessmentTask task = taskMapper.selectById(score.getTaskId());
        if (task == null) {
            throw new BusinessException(404, "考核任务不存在: " + score.getTaskId());
        }
        assertAssessor(task);
        // 周期锁定：考核尚未发起或已关闭时拒绝上传凭证
        periodService.assertOngoing(task.getPeriodId(), "上传凭证");
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的凭证文件");
        }
        if (file.getSize() > MAX_EVIDENCE_SIZE) {
            throw new BusinessException(400, "文件大小超过10MB限制");
        }

        // 生成访问 URL（Phase 2.0 简化为占位 URL，文件存储由对象存储服务承接）
        String ext = "";
        String originalName = file.getOriginalFilename();
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf('.'));
        }
        String url = "/uploads/evidence/" + UUID.randomUUID().toString().replace("-", "") + ext;

        score.setEvidenceUrl(url);
        score.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(score);
        return url;
    }

    // 功能：确保评分草稿行存在（凭证上传前需要 scoreId）——已存在则返回其 id，否则插入空 DRAFT 行
    @Transactional
    public Long ensureScore(Long taskId, Long kpiConfigId, String kpiType) {
        AssessmentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "考核任务不存在: " + taskId);
        }
        // 权限校验：当前登录用户必须是该任务的考核人（ADMIN 豁免），否则 403
        assertAssessor(task);
        // 周期锁定：考核尚未发起或已关闭时拒绝评分操作
        periodService.assertOngoing(task.getPeriodId(), "评分操作");
        if (!expectedKpiType(task).equals(kpiType)) {
            throw new BusinessException(400, "指标类型 " + kpiType + " 与任务类型 " + task.getTaskType() + " 不一致");
        }
        AssessmentScore existing = baseMapper.selectOne(new LambdaQueryWrapper<AssessmentScore>()
                .eq(AssessmentScore::getTaskId, taskId)
                .eq(AssessmentScore::getKpiConfigId, kpiConfigId)
                .eq(AssessmentScore::getKpiType, kpiType));
        if (existing != null) {
            return existing.getId();
        }
        AssessmentScore score = new AssessmentScore();
        score.setTaskId(taskId);
        score.setKpiConfigId(kpiConfigId);
        score.setKpiType(kpiType);
        score.setStatus("DRAFT");
        score.setCreatedAt(LocalDateTime.now());
        score.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(score);
        return score.getId();
    }

    // 功能：乐观锁更新任务——version 由 MyBatis-Plus @Version 自动处理，影响行数为0则抛409冲突
    private void updateTaskWithOptimisticLock(AssessmentTask task) {
        int rows = taskMapper.updateById(task);
        if (rows == 0) {
            throw new BusinessException(409, "数据已被他人修改，请刷新后重试");
        }
    }

    // 功能：推导任务对应的 KPI 类型——直接取 taskType
    private String expectedKpiType(AssessmentTask task) {
        return task.getTaskType();
    }

    // 功能：校验 KPI 配置存在性——根据 kpiType 多态查询对应 KPI 表
    private void validateKpiExists(String kpiType, Long kpiConfigId) {
        if (kpiConfigId == null) {
            throw new BusinessException(400, "KPI 指标 ID 不能为空");
        }
        if ("PROJECT".equals(kpiType)) {
            ProjectKpiConfig kpi = projectKpiMapper.selectById(kpiConfigId);
            if (kpi == null) {
                throw new BusinessException(400, "项目 KPI 指标不存在: " + kpiConfigId);
            }
        } else if ("FUNCTIONAL".equals(kpiType)) {
            FuncKpiConfig kpi = funcKpiMapper.selectById(kpiConfigId);
            if (kpi == null) {
                throw new BusinessException(400, "职能 KPI 指标不存在: " + kpiConfigId);
            }
        } else {
            throw new BusinessException(400, "无效的指标类型: " + kpiType);
        }
    }

    // 功能：打分权限校验——当前登录用户必须是该任务的考核人（ADMIN 豁免），否则 403
    private void assertAssessor(AssessmentTask task) {
        if (isAdmin()) {
            return;
        }
        if (task.getAssessorId() == null || !task.getAssessorId().equals(getCurrentEmployeeId())) {
            throw new BusinessException(403, "无权操作该考核任务");
        }
    }

    // 功能：判断当前登录用户是否 ADMIN——打分权限校验豁免项
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // 功能：从 Spring Security 上下文取当前用户名，反查员工工号——用于打分权限隔离
    private String getCurrentEmployeeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        return user != null ? user.getEmployeeId() : null;
    }

    // 功能：评分项——提交/草稿时传入的单条 KPI 得分
    @lombok.Data
    public static class ScoreItem {
        private Long kpiConfigId;
        private String kpiType;
        private BigDecimal score;
        private String evidenceUrl;
    }
}
