// 模块用途：员工管理业务逻辑——CRUD、工号唯一校验、直属上级存在校验、删除前引用检查
// 依赖文件：EmployeeMapper.java, Employee.java, EmployeeDTO.java, BaseService.java, BusinessException.java
// 修改注意：新增校验规则只在 validate 方法内部添加，不要改动方法签名
package com.jifeng.assessment.employee;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployeeService extends BaseService<EmployeeMapper, Employee> {

    private final ProjectRoleAssignmentMapper projectRoleAssignmentMapper;

    // 功能：分页查询员工列表，支持按关键字（姓名/工号）、岗位分类、状态筛选
    public PageResult<EmployeeDTO> listEmployees(PageQuery query, String category, String status) {
        LambdaQueryWrapper<Employee> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w
                    .like(Employee::getEmployeeId, query.getKeyword())
                    .or()
                    .like(Employee::getName, query.getKeyword()));
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(Employee::getCategory, category);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Employee::getStatus, status);
        }
        wrapper.orderByAsc(Employee::getEmployeeId);

        PageResult<Employee> page = selectPage(query, wrapper);
        List<EmployeeDTO> dtoList = page.getList().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(page.getTotal(), page.getPage(), page.getSize(), dtoList);
    }

    // 功能：根据工号查询单个员工，不存在返回null
    public EmployeeDTO getEmployee(String employeeId) {
        Employee emp = baseMapper.selectById(employeeId);
        return emp != null ? toDTO(emp) : null;
    }

    // 功能：新增员工——校验工号不重复、必填字段完整、直属上级工号存在，通过后写入数据库
    @Transactional
    public EmployeeDTO createEmployee(Employee employee) {
        validateOnCreate(employee);
        try {
            baseMapper.insert(employee);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "工号" + employee.getEmployeeId() + "已存在");
        }
        return toDTO(employee);
    }

    // 功能：更新员工信息——使用乐观锁防止并发覆盖，校验直属上级存在且状态为ACTIVE
    @Transactional
    public EmployeeDTO updateEmployee(Employee employee) {
        Employee existing = baseMapper.selectById(employee.getEmployeeId());
        if (existing == null) {
            throw new BusinessException(404, "员工不存在: " + employee.getEmployeeId());
        }
        if (StringUtils.hasText(employee.getDirectLeaderId())) {
            Employee leader = baseMapper.selectById(employee.getDirectLeaderId());
            if (leader == null) {
                throw new BusinessException(400, "直属上级工号不存在: " + employee.getDirectLeaderId());
            }
            if (!"ACTIVE".equals(leader.getStatus())) {
                throw new BusinessException(400, "直属上级状态无效，仅允许 ACTIVE 员工担任上级");
            }
        }
        employee.setUpdatedAt(LocalDateTime.now());
        employee.setVersion(existing.getVersion());
        updateWithOptimisticLock(employee);
        return toDTO(baseMapper.selectById(employee.getEmployeeId()));
    }

    // 功能：删除员工——逻辑删除（MyBatis-Plus自动将deleteById转为UPDATE SET deleted=1），删除前检查项目角色分配引用
    @Transactional
    public void deleteEmployee(String employeeId) {
        Employee existing = baseMapper.selectById(employeeId);
        if (existing == null) {
            throw new BusinessException(404, "员工不存在: " + employeeId);
        }
        LambdaQueryWrapper<ProjectRoleAssignment> praWrapper = new LambdaQueryWrapper<>();
        praWrapper.eq(ProjectRoleAssignment::getEmployeeId, employeeId);
        if (projectRoleAssignmentMapper.selectCount(praWrapper) > 0) {
            throw new BusinessException(409, "该员工已被分配到项目角色，请先取消分配后再删除");
        }
        baseMapper.deleteById(employeeId);
    }

    // 功能：将 Employee 实体转为 EmployeeDTO，过滤逻辑删除标记等内部字段
    private EmployeeDTO toDTO(Employee emp) {
        EmployeeDTO dto = new EmployeeDTO();
        dto.setEmployeeId(emp.getEmployeeId());
        dto.setName(emp.getName());
        dto.setEmail(emp.getEmail());
        dto.setCategory(emp.getCategory());
        dto.setPosition(emp.getPosition());
        dto.setOrgName(emp.getOrgName());
        dto.setDirectLeaderId(emp.getDirectLeaderId());
        dto.setStatus(emp.getStatus());
        dto.setCreatedAt(emp.getCreatedAt());
        dto.setUpdatedAt(emp.getUpdatedAt());
        return dto;
    }

    // 功能：新增员工前的业务校验——工号格式、必填字段、唯一性、直属上级存在
    private void validateOnCreate(Employee employee) {
        EmployeeValidator.validateEmployeeId(employee.getEmployeeId());
        EmployeeValidator.validateEmail(employee.getEmail());
        EmployeeValidator.validateStatus(employee.getStatus());
        if (!StringUtils.hasText(employee.getName())) {
            throw new BusinessException(400, "姓名不能为空");
        }
        // 工号唯一校验：查询同工号的未删除记录
        if (baseMapper.selectById(employee.getEmployeeId()) != null) {
            throw new BusinessException(409, "工号" + employee.getEmployeeId() + "已存在");
        }
        // 直属上级存在校验
        if (StringUtils.hasText(employee.getDirectLeaderId())) {
            Employee leader = baseMapper.selectById(employee.getDirectLeaderId());
            if (leader == null) {
                throw new BusinessException(400, "直属上级工号不存在: " + employee.getDirectLeaderId());
            }
        }
    }
}
