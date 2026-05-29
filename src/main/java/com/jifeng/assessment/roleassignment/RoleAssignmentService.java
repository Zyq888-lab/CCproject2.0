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

    // 功能：查询项目下所有角色分配——关联查询员工姓名
    public List<ProjectRoleAssignmentDTO> listAssignments(String projectCode) {
        Project project = projectMapper.selectById(projectCode);
        if (project == null) {
            throw new BusinessException(404, "项目不存在: " + projectCode);
        }
        LambdaQueryWrapper<ProjectRoleAssignment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectRoleAssignment::getProjectCode, projectCode)
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
            String employeeId, Boolean isPrimaryPd) {
        Page<ProjectRoleAssignmentSummaryDTO> mpPage = new Page<>(page, size);
        Page<ProjectRoleAssignmentSummaryDTO> result = baseMapper.selectSummaryPage(
                mpPage, projectCode, projectStage, roleCode, employeeId, isPrimaryPd);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    // 功能：分配人员到项目角色——校验项目、员工、角色均存在且未被重复分配
    @Transactional
    public ProjectRoleAssignmentDTO assignEmployee(String projectCode, String roleCode, String employeeId) {
        if (!StringUtils.hasText(projectCode)) {
            throw new BusinessException(400, "项目编码不能为空");
        }
        if (!StringUtils.hasText(roleCode)) {
            throw new BusinessException(400, "角色编码不能为空");
        }
        if (!StringUtils.hasText(employeeId)) {
            throw new BusinessException(400, "员工工号不能为空");
        }

        Project project = projectMapper.selectById(projectCode);
        if (project == null) {
            throw new BusinessException(404, "项目不存在: " + projectCode);
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
                .eq(ProjectRoleAssignment::getProjectRoleCode, roleCode)
                .eq(ProjectRoleAssignment::getEmployeeId, employeeId);
        if (baseMapper.selectCount(dupWrapper) > 0) {
            throw new BusinessException(409,
                    "员工" + employeeId + "已被分配到项目" + projectCode + "的角色" + roleCode);
        }

        ProjectRoleAssignment assignment = new ProjectRoleAssignment();
        assignment.setProjectCode(projectCode);
        assignment.setProjectRoleCode(roleCode);
        assignment.setEmployeeId(employeeId);
        assignment.setIsPrimaryPd(false);
        try {
            baseMapper.insert(assignment);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409,
                    "员工" + employeeId + "已被分配到项目" + projectCode + "的角色" + roleCode);
        }

        return toDTO(assignment, employee.getName());
    }

    // 功能：标记为PD负责人——先取消同项目内已有PD负责人，再设置当前分配
    @Transactional
    public ProjectRoleAssignmentDTO markPrimaryPd(Long assignmentId) {
        ProjectRoleAssignment assignment = baseMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BusinessException(404, "分配记录不存在: " + assignmentId);
        }
        if (!"PD".equals(assignment.getProjectRoleCode())) {
            throw new BusinessException(400, "仅PD角色的分配可标记为PD负责人");
        }

        // 取消同项目内已有的PD负责人标记
        LambdaQueryWrapper<ProjectRoleAssignment> unmarkWrapper = new LambdaQueryWrapper<>();
        unmarkWrapper.eq(ProjectRoleAssignment::getProjectCode, assignment.getProjectCode())
                .eq(ProjectRoleAssignment::getIsPrimaryPd, true);
        List<ProjectRoleAssignment> existingPrimary = baseMapper.selectList(unmarkWrapper);
        for (ProjectRoleAssignment pa : existingPrimary) {
            pa.setIsPrimaryPd(false);
            updateWithOptimisticLock(pa);
        }

        assignment.setIsPrimaryPd(true);
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

    // 功能：将实体转为DTO，关联员工姓名
    private ProjectRoleAssignmentDTO toDTO(ProjectRoleAssignment assignment, String employeeName) {
        ProjectRoleAssignmentDTO dto = new ProjectRoleAssignmentDTO();
        dto.setId(assignment.getId());
        dto.setProjectCode(assignment.getProjectCode());
        dto.setProjectRoleCode(assignment.getProjectRoleCode());
        dto.setEmployeeId(assignment.getEmployeeId());
        dto.setEmployeeName(employeeName);
        dto.setIsPrimaryPd(assignment.getIsPrimaryPd());
        dto.setCreatedAt(assignment.getCreatedAt());
        return dto;
    }
}
