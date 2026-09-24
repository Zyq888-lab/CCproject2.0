// 模块用途：项目角色分配业务逻辑——分配人员、标记PD负责人、移除分配、汇总查询
// 依赖文件：ProjectRoleAssignmentMapper.java, ProjectMapper.java, EmployeeMapper.java, ProjectRoleMapper.java
// 修改注意：标记PD负责人时需先取消同项目内已有PD负责人，保证唯一性
package com.jifeng.assessment.roleassignment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.project.Project;
import com.jifeng.assessment.project.ProjectMapper;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleAssignmentService extends BaseService<ProjectRoleAssignmentMapper, ProjectRoleAssignment> {

    private final ProjectMapper projectMapper;
    private final EmployeeMapper employeeMapper;
    private final ProjectRoleMapper projectRoleMapper;

    // 功能：查询项目特定阶段下的所有角色分配——关联查询员工姓名
    public List<ProjectRoleAssignmentDTO> listAssignments(String projectCode, String projectStage) {
        Project project = projectMapper.selectByCodeAndStage(projectCode, projectStage);
        if (project == null) {
            throw new BusinessException(404, "项目不存在: " + projectCode + " / " + projectStage);
        }
        LambdaQueryWrapper<ProjectRoleAssignment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectRoleAssignment::getProjectCode, projectCode)
                .eq(ProjectRoleAssignment::getProjectStage, projectStage)
                .orderByAsc(ProjectRoleAssignment::getId);
        List<ProjectRoleAssignment> assignments = baseMapper.selectList(wrapper);

        List<String> employeeIds = assignments.stream()
                .map(ProjectRoleAssignment::getEmployeeId)
                .distinct()
                .toList();
        Map<String, String> employeeNameMap = employeeIds.isEmpty() ? Map.of() :
                employeeMapper.selectBatchIds(employeeIds).stream()
                        .collect(Collectors.toMap(Employee::getEmployeeId, Employee::getName, (a, b) -> a));

        return assignments.stream()
                .map(a -> toDTO(a, employeeNameMap.get(a.getEmployeeId())))
                .toList();
    }

    // 功能：跨项目角色分配汇总查询——四表JOIN，支持多条件筛选和分页
    public PageResult<ProjectRoleAssignmentSummaryDTO> listSummary(
            int page, int size,
            String projectCode, String projectStage, String roleCode,
            String employeeId, Boolean isPrimary) {
        Page<ProjectRoleAssignmentSummaryDTO> mpPage = new Page<>(page, size);
        Page<ProjectRoleAssignmentSummaryDTO> result = baseMapper.selectSummaryPage(
                mpPage, projectCode, projectStage, roleCode, employeeId, isPrimary);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    // 功能：分配人员到项目角色——校验项目、员工、角色均存在且未被重复分配
    @Transactional
    public ProjectRoleAssignmentDTO assignEmployee(String projectCode, String projectStage,
            String roleCode, String employeeId) {
        if (!StringUtils.hasText(projectCode)) {
            throw new BusinessException(400, "项目编码不能为空");
        }
        if (!StringUtils.hasText(projectStage)) {
            throw new BusinessException(400, "项目阶段不能为空");
        }
        if (!StringUtils.hasText(roleCode)) {
            throw new BusinessException(400, "角色编码不能为空");
        }
        if (!StringUtils.hasText(employeeId)) {
            throw new BusinessException(400, "员工工号不能为空");
        }

        Project project = projectMapper.selectByCodeAndStage(projectCode, projectStage);
        if (project == null) {
            throw new BusinessException(404, "项目不存在: " + projectCode + " / " + projectStage);
        }
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new BusinessException(404, "员工不存在: " + employeeId);
        }
        ProjectRole role = projectRoleMapper.selectById(roleCode);
        if (role == null) {
            throw new BusinessException(404, "项目角色不存在: " + roleCode);
        }

        // 检查重复分配
        LambdaQueryWrapper<ProjectRoleAssignment> dupWrapper = new LambdaQueryWrapper<>();
        dupWrapper.eq(ProjectRoleAssignment::getProjectCode, projectCode)
                .eq(ProjectRoleAssignment::getProjectStage, projectStage)
                .eq(ProjectRoleAssignment::getProjectRoleCode, roleCode)
                .eq(ProjectRoleAssignment::getEmployeeId, employeeId);
        if (baseMapper.selectCount(dupWrapper) > 0) {
            throw new BusinessException(409,
                    "员工" + employeeId + "已被分配到项目" + projectCode + " " + projectStage + "的角色" + roleCode);
        }

        ProjectRoleAssignment assignment = new ProjectRoleAssignment();
        assignment.setProjectCode(projectCode);
        assignment.setProjectStage(projectStage);
        assignment.setProjectRoleCode(roleCode);
        assignment.setEmployeeId(employeeId);
        assignment.setIsPrimary(false);
        try {
            baseMapper.insert(assignment);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409,
                    "员工" + employeeId + "已被分配到项目" + projectCode + " " + projectStage + "的角色" + roleCode);
        }

        return toDTO(assignment, employee.getName());
    }

    // 功能：标记为该角色主——先取消同(项目,阶段,角色)内已有主标记，再设置当前分配
    @Transactional
    public ProjectRoleAssignmentDTO markPrimary(Long assignmentId) {
        ProjectRoleAssignment assignment = baseMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BusinessException(404, "分配记录不存在: " + assignmentId);
        }

        // 取消同(项目,阶段,角色)内已有的主标记——只清同角色，不误删其它阶段/角色的主
        LambdaQueryWrapper<ProjectRoleAssignment> unmarkWrapper = new LambdaQueryWrapper<>();
        unmarkWrapper.eq(ProjectRoleAssignment::getProjectCode, assignment.getProjectCode())
                .eq(ProjectRoleAssignment::getProjectStage, assignment.getProjectStage())
                .eq(ProjectRoleAssignment::getProjectRoleCode, assignment.getProjectRoleCode())
                .eq(ProjectRoleAssignment::getIsPrimary, true);
        List<ProjectRoleAssignment> existingPrimary = baseMapper.selectList(unmarkWrapper);
        for (ProjectRoleAssignment pa : existingPrimary) {
            pa.setIsPrimary(false);
            updateWithOptimisticLock(pa);
        }

        assignment.setIsPrimary(true);
        updateWithOptimisticLock(assignment);

        Employee employee = employeeMapper.selectById(assignment.getEmployeeId());
        return toDTO(assignment, employee != null ? employee.getName() : null);
    }

    // 功能：取消该角色主标记——仅清 is_primary，保留角色分配本身
    @Transactional
    public ProjectRoleAssignmentDTO unmarkPrimary(Long assignmentId) {
        ProjectRoleAssignment assignment = baseMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BusinessException(404, "分配记录不存在: " + assignmentId);
        }
        if (!Boolean.TRUE.equals(assignment.getIsPrimary())) {
            throw new BusinessException(400, "该分配不是主标记，无需取消");
        }

        assignment.setIsPrimary(false);
        updateWithOptimisticLock(assignment);

        Employee employee = employeeMapper.selectById(assignment.getEmployeeId());
        return toDTO(assignment, employee != null ? employee.getName() : null);
    }

    // 功能：移除角色分配——逻辑删除
    @Transactional
    public void removeAssignment(Long assignmentId) {
        ProjectRoleAssignment assignment = baseMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BusinessException(404, "分配记录不存在: " + assignmentId);
        }
        baseMapper.deleteById(assignmentId);
    }

    // 功能：跨阶段同步主总裁——将 sourceStage 的主总裁分配到同项目其它阶段（PRESIDENT 且 is_primary）
    //   用于消除「一项目多主总裁」冲突：目标阶段已有其它主总裁时先取消，再置 sourceStage 主总裁为主
    @Transactional
    public Map<String, Object> syncPresidentAcrossStages(String projectCode, String sourceStage) {
        Project sourceProject = projectMapper.selectByCodeAndStage(projectCode, sourceStage);
        if (sourceProject == null) {
            throw new BusinessException(404, "项目不存在: " + projectCode + " / " + sourceStage);
        }
        // 当前阶段主总裁
        List<ProjectRoleAssignment> sourcePrimary = baseMapper.selectList(
                new LambdaQueryWrapper<ProjectRoleAssignment>()
                        .eq(ProjectRoleAssignment::getProjectCode, projectCode)
                        .eq(ProjectRoleAssignment::getProjectStage, sourceStage)
                        .eq(ProjectRoleAssignment::getProjectRoleCode, "PRESIDENT")
                        .eq(ProjectRoleAssignment::getIsPrimary, true)
                        .eq(ProjectRoleAssignment::getDeleted, 0));
        if (sourcePrimary.isEmpty()) {
            throw new BusinessException(400, "当前阶段未分配主总裁，无法跨阶段同步");
        }
        String presidentEmployeeId = sourcePrimary.get(0).getEmployeeId();

        // 同项目其它阶段（按阶段排序去重）
        List<String> otherStages = projectMapper.selectByCode(projectCode).stream()
                .map(Project::getProjectStage)
                .filter(stage -> !sourceStage.equals(stage))
                .distinct()
                .toList();

        int synced = 0;
        for (String stage : otherStages) {
            syncPresidentToStage(projectCode, stage, presidentEmployeeId);
            synced++;
        }
        return Map.of("syncedStages", synced, "presidentEmployeeId", presidentEmployeeId);
    }

    // 功能：将指定员工设为目标阶段的主总裁——先取消该阶段已有主总裁标记，再置目标员工为主（无分配则新建）
    private void syncPresidentToStage(String projectCode, String stage, String employeeId) {
        List<ProjectRoleAssignment> existing = baseMapper.selectList(
                new LambdaQueryWrapper<ProjectRoleAssignment>()
                        .eq(ProjectRoleAssignment::getProjectCode, projectCode)
                        .eq(ProjectRoleAssignment::getProjectStage, stage)
                        .eq(ProjectRoleAssignment::getProjectRoleCode, "PRESIDENT")
                        .eq(ProjectRoleAssignment::getDeleted, 0));
        // 先取消该阶段已有的主总裁标记
        for (ProjectRoleAssignment a : existing) {
            if (Boolean.TRUE.equals(a.getIsPrimary())) {
                a.setIsPrimary(false);
                updateWithOptimisticLock(a);
            }
        }
        ProjectRoleAssignment target = existing.stream()
                .filter(a -> employeeId.equals(a.getEmployeeId()))
                .findFirst().orElse(null);
        if (target == null) {
            target = new ProjectRoleAssignment();
            target.setProjectCode(projectCode);
            target.setProjectStage(stage);
            target.setProjectRoleCode("PRESIDENT");
            target.setEmployeeId(employeeId);
            target.setIsPrimary(true);
            baseMapper.insert(target);
        } else {
            target.setIsPrimary(true);
            updateWithOptimisticLock(target);
        }
    }

    // 功能：将实体转为DTO，关联员工姓名
    private ProjectRoleAssignmentDTO toDTO(ProjectRoleAssignment assignment, String employeeName) {
        ProjectRoleAssignmentDTO dto = new ProjectRoleAssignmentDTO();
        dto.setId(assignment.getId());
        dto.setProjectCode(assignment.getProjectCode());
        dto.setProjectRoleCode(assignment.getProjectRoleCode());
        dto.setEmployeeId(assignment.getEmployeeId());
        dto.setEmployeeName(employeeName);
        dto.setIsPrimary(assignment.getIsPrimary());
        dto.setCreatedAt(assignment.getCreatedAt());
        return dto;
    }
}
