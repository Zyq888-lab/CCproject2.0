// 模块用途：仪表盘服务——配置进度统计 + 差异报告（阶段2占位）
// 依赖文件：EmployeeMapper.java, ProjectRoleMapper.java, ProjectMapper.java, PositionConfigMapper.java, ProjectKpiMapper.java, FuncKpiMapper.java
// 修改注意：新增配置实体时同步更新configProgress()的统计项列表
package com.jifeng.assessment.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.kpi.ProjectKpiMapper;
import com.jifeng.assessment.position.PositionConfigMapper;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final EmployeeMapper employeeMapper;
    private final ProjectRoleMapper projectRoleMapper;
    private final ProjectMapper projectMapper;
    private final PositionConfigMapper positionConfigMapper;
    private final ProjectKpiMapper projectKpiMapper;
    private final FuncKpiMapper funcKpiMapper;

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

    private ConfigProgressItem item(String key, String label, long count, String link) {
        return new ConfigProgressItem(key, label, count, count > 0 ? STATUS_CONFIGURED : STATUS_PENDING, link);
    }
}
