// 模块用途：仪表盘服务——配置进度统计 + 待处理任务计数 + 差异报告
// 依赖文件：EmployeeMapper.java, ProjectRoleMapper.java, ProjectMapper.java, PositionConfigMapper.java, ProjectKpiMapper.java, FuncKpiMapper.java, TaskMapper.java, ParticipationMapper.java, DiscrepancyLogMapper.java
// 修改注意：新增配置实体时同步更新configProgress()的统计项列表；pendingCount按角色区分数据源
package com.jifeng.assessment.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.participation.EmployeeProjectParticipation;
import com.jifeng.assessment.participation.ParticipationMapper;
import com.jifeng.assessment.position.PositionConfigMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import com.jifeng.assessment.task.AssessmentTask;
import com.jifeng.assessment.task.DiscrepancyLog;
import com.jifeng.assessment.task.DiscrepancyLogMapper;
import com.jifeng.assessment.task.TaskMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final EmployeeMapper employeeMapper;
    private final ProjectRoleMapper projectRoleMapper;
    private final ProjectMapper projectMapper;
    private final PositionConfigMapper positionConfigMapper;
    private final ProjectKpiMapper projectKpiMapper;
    private final FuncKpiMapper funcKpiMapper;
    private final TaskMapper taskMapper;
    private final ParticipationMapper participationMapper;
    private final DiscrepancyLogMapper discrepancyLogMapper;
    private final SysUserMapper sysUserMapper;
    private final ProjectRoleAssignmentMapper roleAssignmentMapper;

    public static final String STATUS_CONFIGURED = "已配置";
    public static final String STATUS_PENDING = "待配置";

    public record ConfigProgressItem(String key, String label, long count, String status, String link) {}

    public record DashboardSummary(long employeeCount, long projectRoleCount, long projectCount,
                                   long positionConfigCount, long kpiCount, long configuredCount,
                                   long totalModules, int completionPercent) {}

    // 功能：仪表盘摘要——聚合所有配置模块的数据量
    public DashboardSummary summary() {
        List<ConfigProgressItem> items = configProgress();
        long total = items.size();
        long configured = items.stream().filter(i -> i.count > 0).count();
        int pct = total > 0 ? (int) Math.round((double) configured / total * 100) : 0;
        return new DashboardSummary(
                items.stream().filter(i -> "employee".equals(i.key)).findFirst().map(ConfigProgressItem::count).orElse(0L),
                items.stream().filter(i -> "projectRole".equals(i.key)).findFirst().map(ConfigProgressItem::count).orElse(0L),
                items.stream().filter(i -> "project".equals(i.key)).findFirst().map(ConfigProgressItem::count).orElse(0L),
                items.stream().filter(i -> "positionConfig".equals(i.key)).findFirst().map(ConfigProgressItem::count).orElse(0L),
                items.stream().filter(i -> "kpi".equals(i.key)).findFirst().map(ConfigProgressItem::count).orElse(0L),
                configured, total, pct);
    }

    // 功能：统计各配置模块的数据量，count=0时status为"待配置"
    public List<ConfigProgressItem> configProgress() {
        long employeeCount = employeeMapper.selectCount(new LambdaQueryWrapper<>());
        long projectRoleCount = projectRoleMapper.selectCount(new LambdaQueryWrapper<>());
        long projectCount = projectMapper.selectCount(new LambdaQueryWrapper<>());
        long positionConfigCount = positionConfigMapper.selectCount(new LambdaQueryWrapper<>());
        long kpiCount = projectKpiMapper.selectCount(new LambdaQueryWrapper<>())
                + funcKpiMapper.selectCount(new LambdaQueryWrapper<>());

        return List.of(
                item("employee", "员工", employeeCount, "/employees"),
                item("projectRole", "项目角色", projectRoleCount, "/project-roles"),
                item("project", "项目", projectCount, "/projects"),
                item("positionConfig", "岗位考核配置", positionConfigCount, "/position-configs"),
                item("kpi", "KPI指标", kpiCount, "/kpi-configs")
        );
    }

    // 功能：差异报告——阶段2批量生成考核任务后显示异常清单，阶段1返回空列表
    public List<String> diffReport() {
        return List.of();
    }

    // 功能：待处理任务计数——按角色返回不同数据，且按当前用户 employeeId 过滤：
    //   评估人=自己待评分任务数、员工=自己待参与项目数(PENDING参与)、
    //   PM=自己项目的待审批参与数、ADMIN=差异报告未处理数(resolved=false)
    public long pendingCount() {
        String role = getPrimaryRole();
        String employeeId = getCurrentEmployeeId();
        switch (role) {
            case "ADMIN":
                return discrepancyLogMapper.selectCount(new LambdaQueryWrapper<DiscrepancyLog>()
                        .eq(DiscrepancyLog::getResolved, false));
            case "评估人":
                // 与 TaskService.listTasks(scope=pending) 对齐：只统计可操作状态，排除历史 SELF 自评，且仅限已发起(ONGOING)周期
                return taskMapper.selectCount(new LambdaQueryWrapper<AssessmentTask>()
                        .eq(AssessmentTask::getAssessorId, employeeId)
                        .in(AssessmentTask::getStatus, "PENDING", "IN_PROGRESS")
                        .ne(AssessmentTask::getTaskType, "SELF")
                        .inSql(AssessmentTask::getPeriodId,
                                "SELECT period_id FROM assessment_period WHERE status = 'ONGOING' AND deleted = 0"));
            case "员工":
                return participationMapper.selectCount(new LambdaQueryWrapper<EmployeeProjectParticipation>()
                        .eq(EmployeeProjectParticipation::getEmployeeId, employeeId)
                        .eq(EmployeeProjectParticipation::getStatus, "PENDING"));
            case "PM":
                return countPendingParticipationForPm(employeeId);
            default:
                return 0;
        }
    }

    // 功能：统计 PM 自己项目的待审批参与数——先查 PM 分配的项目编码集合，再按项目过滤参与记录
    private long countPendingParticipationForPm(String employeeId) {
        List<ProjectRoleAssignment> assignments = roleAssignmentMapper.selectList(
                new LambdaQueryWrapper<ProjectRoleAssignment>()
                        .eq(ProjectRoleAssignment::getEmployeeId, employeeId)
                        .eq(ProjectRoleAssignment::getDeleted, 0));
        List<String> projectCodes = assignments.stream()
                .map(ProjectRoleAssignment::getProjectCode)
                .distinct()
                .toList();
        if (projectCodes.isEmpty()) {
            return 0;
        }
        return participationMapper.selectCount(new LambdaQueryWrapper<EmployeeProjectParticipation>()
                .in(EmployeeProjectParticipation::getProjectCode, projectCodes)
                .eq(EmployeeProjectParticipation::getStatus, "PENDING"));
    }

    // 功能：从 SecurityContext 用户名反查当前用户 employeeId，未认证返回 null
    private String getCurrentEmployeeId() {
        String username = getCurrentUsername();
        if (username.isEmpty()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        return user != null ? user.getEmployeeId() : null;
    }

    // 功能：从 SecurityContext 获取当前登录用户名，未认证返回空串
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "";
        }
        return auth.getName();
    }

    // 功能：查询未处理的差异记录——仅返回 resolved=false 的异常项，批量 join 员工姓名/项目名避免 N+1
    public List<DiscrepancyLogDTO> pendingDiscrepancies() {
        List<DiscrepancyLog> logs = discrepancyLogMapper.selectList(new LambdaQueryWrapper<DiscrepancyLog>()
                .eq(DiscrepancyLog::getResolved, false)
                .orderByAsc(DiscrepancyLog::getId));
        if (logs.isEmpty()) {
            return List.of();
        }

        // 批量补员工姓名
        Map<String, String> employeeNames = new HashMap<>();
        List<String> employeeIds = logs.stream()
                .map(DiscrepancyLog::getEmployeeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!employeeIds.isEmpty()) {
            employeeMapper.selectBatchIds(employeeIds)
                    .forEach(e -> employeeNames.put(e.getEmployeeId(), e.getName()));
        }

        // 批量补项目名——按 project_code 聚合（同一 code 各阶段共用 project_name）
        Map<String, String> projectNames = new HashMap<>();
        List<String> projectCodes = logs.stream()
                .map(DiscrepancyLog::getProjectCode)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!projectCodes.isEmpty()) {
            projectMapper.selectList(new LambdaQueryWrapper<Project>()
                            .in(Project::getProjectCode, projectCodes)
                            .eq(Project::getDeleted, 0))
                    .forEach(p -> projectNames.putIfAbsent(p.getProjectCode(), p.getProjectName()));
        }

        return logs.stream()
                .map(log -> toDiscrepancyDTO(log, employeeNames, projectNames))
                .toList();
    }

    // 功能：差异记录转 DTO——补员工姓名/项目名
    private DiscrepancyLogDTO toDiscrepancyDTO(DiscrepancyLog log,
                                               Map<String, String> employeeNames,
                                               Map<String, String> projectNames) {
        DiscrepancyLogDTO dto = new DiscrepancyLogDTO();
        dto.setId(log.getId());
        dto.setPeriodId(log.getPeriodId());
        dto.setEmployeeId(log.getEmployeeId());
        dto.setEmployeeName(employeeNames.get(log.getEmployeeId()));
        dto.setProjectCode(log.getProjectCode());
        dto.setProjectStage(log.getProjectStage());
        dto.setProjectName(log.getProjectCode() == null ? null : projectNames.get(log.getProjectCode()));
        dto.setType(log.getType());
        dto.setDetail(log.getDetail());
        dto.setResolved(log.getResolved());
        dto.setCreatedAt(log.getCreatedAt());
        return dto;
    }

    // 功能：标记差异已处理——ADMIN 补完配置后销账，resolved 置 true（幂等）
    public void resolveDiscrepancy(Long id) {
        DiscrepancyLog log = discrepancyLogMapper.selectById(id);
        if (log == null) {
            throw new BusinessException(404, "差异记录不存在: " + id);
        }
        if (Boolean.TRUE.equals(log.getResolved())) {
            return;
        }
        DiscrepancyLog update = new DiscrepancyLog();
        update.setId(id);
        update.setResolved(true);
        update.setUpdatedAt(LocalDateTime.now());
        discrepancyLogMapper.updateById(update);
    }

    // 功能：获取当前用户主角色——取权限列表中第一个匹配的已知角色，未认证返回空
    private String getPrimaryRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "";
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String a = authority.getAuthority();
            for (String role : new String[]{"ADMIN", "PM", "PD", "评估人", "员工"}) {
                if (a.equals("ROLE_" + role)) {
                    return role;
                }
            }
        }
        return "";
    }

    private ConfigProgressItem item(String key, String label, long count, String link) {
        return new ConfigProgressItem(key, label, count, count > 0 ? STATUS_CONFIGURED : STATUS_PENDING, link);
    }
}
