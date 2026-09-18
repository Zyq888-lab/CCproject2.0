// 模块用途：考核任务生成引擎——根据岗位配置+项目参与+角色分配自动匹配考核人并生成任务
// 依赖文件：TaskMapper.java, ParticipationMapper.java, PositionConfigMapper.java, PositionAssessorRoleMapper.java, ProjectRoleAssignmentMapper.java, EmployeeMapper.java, DiscrepancyLogMapper.java
// 修改注意：launch 使用 Savepoint 容错——缺配置员工跳过不抛异常；增量生成用 INSERT ON CONFLICT DO NOTHING
package com.jifeng.assessment.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.participation.EmployeeProjectParticipation;
import com.jifeng.assessment.participation.ParticipationMapper;
import com.jifeng.assessment.period.AssessmentPeriod;
import com.jifeng.assessment.period.PeriodMapper;
import com.jifeng.assessment.position.PositionAssessorRoleConfig;
import com.jifeng.assessment.position.PositionAssessorRoleMapper;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.notification.Notification;
import com.jifeng.assessment.notification.NotificationService;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import com.jifeng.assessment.user.UserRole;
import com.jifeng.assessment.user.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskGeneratorService {

    private final TaskMapper taskMapper;
    private final ParticipationMapper participationMapper;
    private final PositionConfigMapper positionConfigMapper;
    private final PositionAssessorRoleMapper assessorRoleMapper;
    private final ProjectRoleAssignmentMapper roleAssignmentMapper;
    private final EmployeeMapper employeeMapper;
    private final FuncKpiMapper funcKpiMapper;
    private final PeriodMapper periodMapper;
    private final DiscrepancyLogMapper discrepancyLogMapper;
    private final NotificationService notificationService;
    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;

    private static final String TASK_TYPE_PROJECT = "PROJECT";
    private static final String TASK_TYPE_FUNCTIONAL = "FUNCTIONAL";
    private static final String STATUS_PENDING = "PENDING";
    private static final String DISCREPANCY_NO_POSITION_CONFIG = "NO_POSITION_CONFIG";
    private static final String DISCREPANCY_NO_ASSESSOR = "NO_ASSESSOR";
    private static final String DISCREPANCY_NO_LEADER = "NO_LEADER";
    private static final String DISCREPANCY_NO_PRIMARY_ASSESSOR = "NO_PRIMARY_ASSESSOR";

    // 自注入代理，用于 REQUIRES_NEW 独立事务的周期状态先行提交（避免 this 自调用绕过 AOP）
    @Lazy
    @Autowired
    private TaskGeneratorService self;

    // 功能：发起考核——周期状态先行提交，遍历员工批量生成考核任务
    // 容错：周期状态更新走 REQUIRES_NEW 独立事务，后续任务生成失败不影响周期进入 ONGOING
    @Transactional
    public LaunchResult launch(String periodId) {
        AssessmentPeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException(404, "考核周期不存在: " + periodId);
        }
        if (!"INIT".equals(period.getStatus())) {
            throw new BusinessException(400, "仅未开始周期可发起");
        }

        // 数据完整性由参与记录/角色分配 + 差异报告保证（缺配置/缺考核人跳过并记录差异），
        // 不再要求项目维度手动确认阶段（stage_confirmed）——项目可跨多个考核周期，阶段确认是项目维度操作，不应阻塞发起考核

        // 周期状态先行提交（REQUIRES_NEW 独立事务，即使后续任务生成失败也不回滚）
        self.markPeriodOngoing(periodId);

        // 预加载配置数据（避免 N+1）
        List<Employee> activeEmployees = employeeMapper.selectList(
                new LambdaQueryWrapper<Employee>().eq(Employee::getStatus, "ACTIVE"));

        int taskCount = 0;
        int discrepancyCount = 0;
        Set<String> allAssessors = new LinkedHashSet<>();
        // 已通知的无主角色组合 (projectCode|projectStage|roleCode)——避免同一无主角色对多个员工重复通知 admin
        Set<String> notifiedNoPrimaryKeys = new HashSet<>();

        // 逐员工生成任务——每个员工独立容错，缺配置/缺考核人跳过并写入差异
        for (Employee emp : activeEmployees) {
            GenerationResult result = generateTasksForEmployee(periodId, emp, notifiedNoPrimaryKeys);
            taskCount += result.taskCount();
            allAssessors.addAll(result.assessorIds());
            for (Discrepancy d : result.discrepancies()) {
                discrepancyLogMapper.insert(buildDiscrepancy(periodId, emp, d.type(), d.projectCode(), d.projectStage(), d.detail()));
                discrepancyCount++;
                log.warn("员工 {} 考核任务生成异常 [{}]: {}", emp.getEmployeeId(), d.type(), d.detail());
            }
        }

        // 功能：任务生成后通知所有被分配任务的评估人
        notifyAssessors(allAssessors);

        return new LaunchResult(taskCount, discrepancyCount);
    }

    // 功能：周期状态更新——REQUIRES_NEW 独立事务，先行提交 ONGOING 状态
    // 原子翻转：WHERE status='INIT' 单条 UPDATE，与 enterCalibration/confirm/close/abort 口径一致，
    //   避免 select-then-updateById 并发下重复置态（read-modify-write 漏检）
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPeriodOngoing(String periodId) {
        periodMapper.updateStatus(periodId, "INIT", "ONGOING");
    }

    // 功能：为单个员工生成考核任务——返回任务数、差异列表、被分配的评估人集合
    private GenerationResult generateTasksForEmployee(String periodId, Employee emp, Set<String> notifiedNoPrimaryKeys) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        int taskCount = 0;
        Set<String> assessorIds = new LinkedHashSet<>();

        // Step 1: 查岗位配置
        PositionAssessmentConfig posConfig = positionConfigMapper.selectOne(
                new LambdaQueryWrapper<PositionAssessmentConfig>()
                        .eq(PositionAssessmentConfig::getCategory, emp.getCategory())
                        .eq(PositionAssessmentConfig::getPosition, emp.getPosition())
                        .last("LIMIT 1"));
        if (posConfig == null) {
            discrepancies.add(new Discrepancy(DISCREPANCY_NO_POSITION_CONFIG, null, null, "缺岗位配置"));
            return new GenerationResult(0, discrepancies, assessorIds);
        }

        // Step 2: 查考核人角色列表
        List<PositionAssessorRoleConfig> assessorRoles = assessorRoleMapper.selectList(
                new LambdaQueryWrapper<PositionAssessorRoleConfig>()
                        .eq(PositionAssessorRoleConfig::getPositionConfigId, posConfig.getId()));

        // Step 3: 查已审批通过的项目参与记录
        List<EmployeeProjectParticipation> participations = participationMapper.selectList(
                new LambdaQueryWrapper<EmployeeProjectParticipation>()
                        .eq(EmployeeProjectParticipation::getEmployeeId, emp.getEmployeeId())
                        .eq(EmployeeProjectParticipation::getPeriodId, periodId)
                        .eq(EmployeeProjectParticipation::getStatus, "APPROVED"));

        // Step 4: 每个项目 × 每个考核角色 × 每个考核人 → 生成 PROJECT 任务
        for (EmployeeProjectParticipation p : participations) {
            for (PositionAssessorRoleConfig role : assessorRoles) {
                List<ProjectRoleAssignment> assignedPersons = roleAssignmentMapper.selectList(
                        new LambdaQueryWrapper<ProjectRoleAssignment>()
                                .eq(ProjectRoleAssignment::getProjectCode, p.getProjectCode())
                                .eq(ProjectRoleAssignment::getProjectStage, p.getProjectStage())
                                .eq(ProjectRoleAssignment::getProjectRoleCode, role.getRoleCode()));

                if (assignedPersons.isEmpty()) {
                    // 降级策略：使用直属上级代考；上级也为空则记录差异
                    if (emp.getDirectLeaderId() != null) {
                        insertIgnore(periodId, emp.getDirectLeaderId(), emp.getEmployeeId(),
                                p.getProjectCode(), p.getProjectStage(), TASK_TYPE_PROJECT);
                        assessorIds.add(emp.getDirectLeaderId());
                        taskCount++;
                    } else {
                        discrepancies.add(new Discrepancy(DISCREPANCY_NO_ASSESSOR,
                                p.getProjectCode(), p.getProjectStage(),
                                "项目" + p.getProjectCode() + "角色" + role.getRoleCode() + "无考核人"));
                    }
                    continue;
                }
                // 收敛：同角色多人时只发标主者；多人无主则跳过该角色 + 记差异 + 通知 admin
                List<ProjectRoleAssignment> primaries = resolvePrimaryAssessors(assignedPersons);
                if (primaries.isEmpty()) {
                    discrepancies.add(new Discrepancy(DISCREPANCY_NO_PRIMARY_ASSESSOR,
                            p.getProjectCode(), p.getProjectStage(),
                            "项目" + p.getProjectCode() + "阶段" + p.getProjectStage()
                                    + "角色" + role.getRoleCode() + "有" + assignedPersons.size() + "人未标主，任务未生成"));
                    // 通知按 (项目,阶段,角色) 去重——launch 逐员工生成，同一无主角色只通知 admin 一次
                    String noPrimaryKey = p.getProjectCode() + "|" + p.getProjectStage() + "|" + role.getRoleCode();
                    if (notifiedNoPrimaryKeys.add(noPrimaryKey)) {
                        notifyAdminsNoPrimary(p.getProjectCode(), p.getProjectStage(), role.getRoleCode(), assignedPersons.size());
                    }
                    continue;
                }
                for (ProjectRoleAssignment assign : primaries) {
                    insertIgnore(periodId, assign.getEmployeeId(), emp.getEmployeeId(),
                            p.getProjectCode(), p.getProjectStage(), TASK_TYPE_PROJECT);
                    assessorIds.add(assign.getEmployeeId());
                    taskCount++;
                }
            }
        }

        // Step 5: 生成 FUNCTIONAL 任务（直属上级考核职能）；无上级则记录差异；无职能 KPI 配置则不生成空职能任务
        // 前置守卫：仅当员工本周期有 APPROVED 参与记录才生成职能考核；未参与项目者跳过（不生成、不记差异）
        if (participations.isEmpty()) {
            // 无参与记录 → 跳过 FUNCTIONAL 任务
        } else if (emp.getDirectLeaderId() == null) {
            discrepancies.add(new Discrepancy(DISCREPANCY_NO_LEADER, null, null, "直属上级为空"));
        } else if (hasFunctionalKpi(emp)) {
            insertIgnore(periodId, emp.getDirectLeaderId(), emp.getEmployeeId(),
                    null, null, TASK_TYPE_FUNCTIONAL);
            assessorIds.add(emp.getDirectLeaderId());
            taskCount++;
        }

        return new GenerationResult(taskCount, discrepancies, assessorIds);
    }

    // 功能：参与记录审批通过后的增量生成——为单条参与记录生成 PROJECT + FUNCTIONAL 任务，并通知评估人
    // 去重：insertIgnore 使用 INSERT ON CONFLICT DO NOTHING，重复触发静默跳过
    @Transactional
    public void onParticipationApproved(EmployeeProjectParticipation participation) {
        // 周期状态闸门：仅已发起(ONGOING)的周期在审批通过时增量生成任务；
        // 未发起(INIT)/已关闭(COMPLETED)/校准(CALIBRATING)等状态交由 launch 统一生成，避免提前暴露评分入口
        AssessmentPeriod period = periodMapper.selectById(participation.getPeriodId());
        if (period == null || !"ONGOING".equals(period.getStatus())) {
            return;
        }

        Employee emp = employeeMapper.selectById(participation.getEmployeeId());
        if (emp == null) {
            return;
        }

        PositionAssessmentConfig posConfig = positionConfigMapper.selectOne(
                new LambdaQueryWrapper<PositionAssessmentConfig>()
                        .eq(PositionAssessmentConfig::getCategory, emp.getCategory())
                        .eq(PositionAssessmentConfig::getPosition, emp.getPosition())
                        .last("LIMIT 1"));
        if (posConfig == null) {
            return; // 缺配置，交由差异报告处理
        }

        List<PositionAssessorRoleConfig> assessorRoles = assessorRoleMapper.selectList(
                new LambdaQueryWrapper<PositionAssessorRoleConfig>()
                        .eq(PositionAssessorRoleConfig::getPositionConfigId, posConfig.getId()));

        Set<String> assessorIds = new LinkedHashSet<>();

        // 为该参与记录生成 PROJECT 任务
        for (PositionAssessorRoleConfig role : assessorRoles) {
            List<ProjectRoleAssignment> assignedPersons = roleAssignmentMapper.selectList(
                    new LambdaQueryWrapper<ProjectRoleAssignment>()
                            .eq(ProjectRoleAssignment::getProjectCode, participation.getProjectCode())
                            .eq(ProjectRoleAssignment::getProjectStage, participation.getProjectStage())
                            .eq(ProjectRoleAssignment::getProjectRoleCode, role.getRoleCode()));

            if (assignedPersons.isEmpty()) {
                if (emp.getDirectLeaderId() != null) {
                    insertIgnore(participation.getPeriodId(), emp.getDirectLeaderId(), emp.getEmployeeId(),
                            participation.getProjectCode(), participation.getProjectStage(), TASK_TYPE_PROJECT);
                    assessorIds.add(emp.getDirectLeaderId());
                }
                continue;
            }
            // 收敛：同角色多人时只发标主者；多人无主则跳过该角色 + 记差异 + 通知 admin
            List<ProjectRoleAssignment> primaries = resolvePrimaryAssessors(assignedPersons);
            if (primaries.isEmpty()) {
                discrepancyLogMapper.insert(buildDiscrepancy(participation.getPeriodId(), emp,
                        DISCREPANCY_NO_PRIMARY_ASSESSOR,
                        participation.getProjectCode(), participation.getProjectStage(),
                        "项目" + participation.getProjectCode() + "阶段" + participation.getProjectStage()
                                + "角色" + role.getRoleCode() + "有" + assignedPersons.size() + "人未标主，任务未生成"));
                notifyAdminsNoPrimary(participation.getProjectCode(), participation.getProjectStage(),
                        role.getRoleCode(), assignedPersons.size());
                continue;
            }
            for (ProjectRoleAssignment assign : primaries) {
                insertIgnore(participation.getPeriodId(), assign.getEmployeeId(), emp.getEmployeeId(),
                        participation.getProjectCode(), participation.getProjectStage(), TASK_TYPE_PROJECT);
                assessorIds.add(assign.getEmployeeId());
            }
        }

        // 确保 FUNCTIONAL 任务存在（幂等插入，已存在则跳过）；无职能 KPI 配置则不生成空职能任务
        if (emp.getDirectLeaderId() != null && hasFunctionalKpi(emp)) {
            insertIgnore(participation.getPeriodId(), emp.getDirectLeaderId(), emp.getEmployeeId(),
                    null, null, TASK_TYPE_FUNCTIONAL);
            assessorIds.add(emp.getDirectLeaderId());
        }

        notifyAssessors(assessorIds);
    }

    // 功能：通知被分配任务的评估人——将 employeeId 映射为 userId 后发站内通知
    private void notifyAssessors(Set<String> assessorEmployeeIds) {
        if (assessorEmployeeIds.isEmpty()) {
            return;
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getEmployeeId, assessorEmployeeIds));
        if (users.isEmpty()) {
            return;
        }
        List<Notification> notifications = users.stream().map(user -> {
            Notification n = new Notification();
            n.setRecipientId(user.getUserId());
            n.setTitle("新的考核任务待评分");
            n.setContent("您有新的考核任务需要评分，请前往任务列表查看。");
            n.setType("TASK_ASSIGNED");
            n.setTargetUrl("/tasks");
            n.setIsRead(false);
            return n;
        }).toList();
        notificationService.notifyBatch(notifications);
    }

    // 功能：幂等插入考核任务——INSERT ON CONFLICT DO NOTHING，重复插入静默跳过
    private void insertIgnore(String periodId, String assessorId, String assesseeId,
                              String projectCode, String projectStage, String taskType) {
        AssessmentTask task = new AssessmentTask();
        task.setPeriodId(periodId);
        task.setAssessorId(assessorId);
        task.setAssesseeId(assesseeId);
        task.setProjectCode(projectCode);
        task.setProjectStage(projectStage);
        task.setTaskType(taskType);
        task.setStatus(STATUS_PENDING);
        taskMapper.insertIgnore(task);
    }

    // 功能：收敛同角色多人的考核人——恰好一个主则只发该人；size==1 照常发；多人无主返回空（调用方记差异+通知 admin）
    private List<ProjectRoleAssignment> resolvePrimaryAssessors(List<ProjectRoleAssignment> assignedPersons) {
        if (assignedPersons.size() == 1) {
            return assignedPersons;
        }
        List<ProjectRoleAssignment> primaries = assignedPersons.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsPrimary()))
                .toList();
        if (primaries.size() > 1) {
            // 防御：DB 部分唯一索引应已拦截双主，若绕过/历史脏数据，只发第一个并告警
            ProjectRoleAssignment first = primaries.get(0);
            log.warn("项目 {} 阶段 {} 角色 {} 存在 {} 个主，仅发 {}",
                    first.getProjectCode(), first.getProjectStage(), first.getProjectRoleCode(),
                    primaries.size(), first.getEmployeeId());
            return List.of(first);
        }
        return primaries;
    }

    // 功能：主动通知全部 ADMIN——同角色多人未标主导致任务跳过时提醒处理
    private void notifyAdminsNoPrimary(String projectCode, String projectStage, String roleCode, int personCount) {
        List<UserRole> adminRoles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleType, "ADMIN"));
        if (adminRoles == null || adminRoles.isEmpty()) {
            return;
        }
        List<String> adminIds = adminRoles.stream()
                .map(UserRole::getUserId)
                .filter(uid -> uid != null && !uid.isBlank())
                .distinct()
                .toList();
        if (adminIds.isEmpty()) {
            return;
        }
        List<Notification> notifications = adminIds.stream().map(id -> {
            Notification n = new Notification();
            n.setRecipientId(id);
            n.setTitle("角色未标记主审批人");
            n.setContent("项目" + projectCode + "阶段" + projectStage + "角色" + roleCode
                    + "有" + personCount + "人未标主，考核任务未生成，请前往角色分配页标记。");
            n.setType("NO_PRIMARY_ASSESSOR");
            n.setTargetUrl("/project/assignment-summary");
            n.setIsRead(false);
            return n;
        }).toList();
        notificationService.notifyBatch(notifications);
    }

    // 功能：判断员工是否有职能 KPI 配置——category+position 无 func_kpi_config 时不生成空职能任务
    private boolean hasFunctionalKpi(Employee emp) {
        Long count = funcKpiMapper.selectCount(new LambdaQueryWrapper<FuncKpiConfig>()
                .eq(FuncKpiConfig::getCategory, emp.getCategory())
                .eq(FuncKpiConfig::getPosition, emp.getPosition())
                .eq(FuncKpiConfig::getIsActive, true));
        return count != null && count > 0;
    }

    // 功能：构建差异报告记录——type 显式传入（NO_POSITION_CONFIG / NO_ASSESSOR / NO_LEADER），
    //   projectCode/projectStage 仅在 NO_ASSESSOR/NO_PRIMARY_ASSESSOR 场景有值
    private DiscrepancyLog buildDiscrepancy(String periodId, Employee emp, String type,
                                            String projectCode, String projectStage, String detail) {
        DiscrepancyLog log = new DiscrepancyLog();
        log.setPeriodId(periodId);
        log.setEmployeeId(emp.getEmployeeId());
        log.setProjectCode(projectCode);
        log.setProjectStage(projectStage);
        log.setType(type);
        log.setDetail(detail);
        log.setResolved(false);
        log.setCreatedAt(LocalDateTime.now());
        log.setUpdatedAt(LocalDateTime.now());
        return log;
    }

    // 功能：发起考核结果封装——任务数 + 差异数
    public record LaunchResult(int taskCount, int discrepancyCount) {
    }

    // 功能：员工任务生成差异项——type + 关联项目/阶段 + 详情（NO_POSITION_CONFIG/NO_LEADER 项目阶段为 null）
    private record Discrepancy(String type, String projectCode, String projectStage, String detail) {
    }

    // 功能：单员工任务生成结果——生成的任务数 + 差异列表 + 被分配的评估人集合
    private record GenerationResult(int taskCount, List<Discrepancy> discrepancies, Set<String> assessorIds) {
    }
}
