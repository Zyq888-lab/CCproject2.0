// 模块用途：员工管理业务逻辑——CRUD、工号唯一校验、直属上级存在校验、删除前引用检查
// 依赖文件：EmployeeMapper.java, Employee.java, EmployeeDTO.java, BaseService.java, BusinessException.java
// 修改注意：新增校验规则只在 validate 方法内部添加，不要改动方法签名
package com.jifeng.assessment.employee;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.positioncategory.PositionCategory;
import com.jifeng.assessment.positioncategory.PositionCategoryMapper;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignment;
import com.jifeng.assessment.roleassignment.ProjectRoleAssignmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService extends BaseService<EmployeeMapper, Employee> {

    private final ProjectRoleAssignmentMapper projectRoleAssignmentMapper;
    private final PositionCategoryMapper positionCategoryMapper;

    // 功能：分页查询员工列表，支持按关键字（姓名/工号）、岗位分类、状态筛选
    public PageResult<EmployeeDTO> listEmployees(PageQuery query, String category, String status,
            String directLeaderId, String orgName) {
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
        if (StringUtils.hasText(directLeaderId)) {
            wrapper.eq(Employee::getDirectLeaderId, directLeaderId);
        }
        if (StringUtils.hasText(orgName)) {
            wrapper.like(Employee::getOrgName, orgName);
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

    // 功能：只读返回全员工号→姓名映射，供任务列表等场景展示姓名（不含敏感字段，权限由控制器放开到所有角色）
    public Map<String, String> listEmployeeNames() {
        List<Employee> employees = baseMapper.selectList(new LambdaQueryWrapper<Employee>()
                .select(Employee::getEmployeeId, Employee::getName));
        Map<String, String> map = new LinkedHashMap<>();
        for (Employee e : employees) {
            if (e.getEmployeeId() != null) {
                map.put(e.getEmployeeId(), e.getName());
            }
        }
        return map;
    }

    // 功能：新增员工——校验工号不重复、必填字段完整、直属上级工号存在，通过后写入数据库
    // 若工号对应的旧记录已被软删除，则先物理删除再插入，允许重新使用该工号
    @Transactional
    public EmployeeDTO createEmployee(Employee employee) {
        validateOnCreate(employee);
        removeSoftDeletedRecord(employee.getEmployeeId());
        try {
            baseMapper.insert(employee);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "工号" + employee.getEmployeeId() + "已存在");
        }
        return toDTO(employee);
    }

    private void removeSoftDeletedRecord(String employeeId) {
        Employee existing = baseMapper.selectByIdIgnoreDeleted(employeeId);
        if (existing != null && existing.getDeleted() != null && existing.getDeleted() == 1) {
            baseMapper.physicalDeleteById(employeeId);
        }
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
        if (StringUtils.hasText(employee.getCategory())) {
            boolean categoryExists = positionCategoryMapper.selectCount(
                    new LambdaQueryWrapper<PositionCategory>()
                            .eq(PositionCategory::getName, employee.getCategory())
                            .eq(PositionCategory::getDeleted, 0)) > 0;
            if (!categoryExists) {
                throw new BusinessException(400, "岗位分类 '" + employee.getCategory() + "' 不存在，请先在岗位分类管理中维护");
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

    // 功能：批量导入员工——逐行校验，有效记录入库，返回各行的导入结果
    @Transactional
    public ImportResult importEmployees(List<Employee> employees) {
        List<ImportResult.RowError> errors = new ArrayList<>();
        int successCount = 0;

        // 批量预加载现有工号和上级工号，避免逐行N+1查询
        Set<String> inputIds = employees.stream()
                .map(Employee::getEmployeeId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        // 物理删除同ID的软删除记录，允许重新使用已删除工号
        for (String id : inputIds) {
            Employee emp = baseMapper.selectByIdIgnoreDeleted(id);
            if (emp != null && emp.getDeleted() != null && emp.getDeleted() == 1) {
                baseMapper.physicalDeleteById(id);
            }
        }
        Set<String> existingIds = inputIds.isEmpty() ? Collections.emptySet()
                : baseMapper.selectBatchIds(inputIds).stream()
                        .map(Employee::getEmployeeId)
                        .collect(Collectors.toSet());

        Set<String> leaderIds = employees.stream()
                .map(Employee::getDirectLeaderId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        leaderIds.removeAll(inputIds); // 已在existingIds中的无需重复查询
        Set<String> existingLeaderIds = leaderIds.isEmpty() ? new HashSet<>()
                : baseMapper.selectBatchIds(leaderIds).stream()
                        .map(Employee::getEmployeeId)
                        .collect(Collectors.toCollection(HashSet::new));
        existingLeaderIds.addAll(existingIds); // 上级也可以是本次导入的员工

        // 批量预加载现有邮箱，用于唯一性校验
        Set<String> inputEmails = employees.stream()
                .map(Employee::getEmail)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Set<String> existingEmails = inputEmails.isEmpty() ? Collections.emptySet()
                : baseMapper.selectList(new LambdaQueryWrapper<Employee>()
                        .in(Employee::getEmail, inputEmails))
                        .stream().map(Employee::getEmail).collect(Collectors.toSet());

        // 批量预加载有效岗位分类
        Set<String> inputCategories = employees.stream()
                .map(Employee::getCategory)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Set<String> validCategories = inputCategories.isEmpty() ? Collections.emptySet()
                : positionCategoryMapper.selectList(new LambdaQueryWrapper<PositionCategory>()
                        .eq(PositionCategory::getDeleted, 0)
                        .in(PositionCategory::getName, inputCategories))
                        .stream().map(PositionCategory::getName).collect(Collectors.toSet());

        int rowNum = 0;
        Set<String> batchEmails = new HashSet<>(); // 防同一批次内重复邮箱
        for (Employee emp : employees) {
            rowNum++;
            try {
                EmployeeValidator.validateEmployeeId(emp.getEmployeeId());
                EmployeeValidator.validateEmail(emp.getEmail());
                EmployeeValidator.validateStatus(emp.getStatus());
                emp.setStatus(EmployeeValidator.mapStatus(emp.getStatus()));
                if (!StringUtils.hasText(emp.getName())) {
                    errors.add(new ImportResult.RowError(rowNum, emp.getEmployeeId(), "姓名不能为空"));
                    continue;
                }
                if (existingIds.contains(emp.getEmployeeId())) {
                    errors.add(new ImportResult.RowError(rowNum, emp.getEmployeeId(), "工号已存在"));
                    continue;
                }
                if (StringUtils.hasText(emp.getEmail())) {
                    if (existingEmails.contains(emp.getEmail())) {
                        errors.add(new ImportResult.RowError(rowNum, emp.getEmployeeId(), "邮箱已存在: " + emp.getEmail()));
                        continue;
                    }
                    if (batchEmails.contains(emp.getEmail())) {
                        errors.add(new ImportResult.RowError(rowNum, emp.getEmployeeId(), "本批次内邮箱重复: " + emp.getEmail()));
                        continue;
                    }
                }
                if (!StringUtils.hasText(emp.getCategory())) {
                    errors.add(new ImportResult.RowError(rowNum, emp.getEmployeeId(),
                            "岗位分类不能为空"));
                    continue;
                }
                if (!validCategories.contains(emp.getCategory())) {
                    errors.add(new ImportResult.RowError(rowNum, emp.getEmployeeId(),
                            "岗位分类 '" + emp.getCategory() + "' 不存在，请先在岗位分类管理中维护"));
                    continue;
                }
                if (StringUtils.hasText(emp.getDirectLeaderId())
                        && !existingLeaderIds.contains(emp.getDirectLeaderId())) {
                    errors.add(new ImportResult.RowError(rowNum, emp.getEmployeeId(),
                            "直属上级工号不存在: " + emp.getDirectLeaderId()));
                    continue;
                }
                baseMapper.insert(emp);
                existingIds.add(emp.getEmployeeId()); // 防同一批次内重复工号
                if (StringUtils.hasText(emp.getEmail())) {
                    batchEmails.add(emp.getEmail()); // 防同一批次内重复邮箱
                }
                successCount++;
            } catch (DuplicateKeyException e) {
                errors.add(new ImportResult.RowError(rowNum,
                        emp.getEmployeeId() != null ? emp.getEmployeeId() : "空",
                        "工号已存在（并发冲突）"));
            } catch (DataAccessException e) {
                errors.add(new ImportResult.RowError(rowNum,
                        emp.getEmployeeId() != null ? emp.getEmployeeId() : "空",
                        "数据写入失败: " + e.getMessage()));
            } catch (Exception e) {
                errors.add(new ImportResult.RowError(rowNum,
                        emp.getEmployeeId() != null ? emp.getEmployeeId() : "空",
                        e.getMessage()));
            }
        }
        return new ImportResult(employees.size(), successCount, errors);
    }

    public record ImportResult(int totalRows, int successCount, List<RowError> errors) {
        public record RowError(int row, String employeeId, String message) {}
    }

    // 功能：新增员工前的业务校验——工号格式、必填字段、唯一性、直属上级存在
    private void validateOnCreate(Employee employee) {
        EmployeeValidator.validateEmployeeId(employee.getEmployeeId());
        EmployeeValidator.validateEmail(employee.getEmail());
        EmployeeValidator.validateStatus(employee.getStatus());
        employee.setStatus(EmployeeValidator.mapStatus(employee.getStatus()));
        if (!StringUtils.hasText(employee.getName())) {
            throw new BusinessException(400, "姓名不能为空");
        }
        if (!StringUtils.hasText(employee.getCategory())) {
            throw new BusinessException(400, "岗位分类不能为空");
        }
        boolean categoryExists = positionCategoryMapper.selectCount(
                new LambdaQueryWrapper<PositionCategory>()
                        .eq(PositionCategory::getName, employee.getCategory())
                        .eq(PositionCategory::getDeleted, 0)) > 0;
        if (!categoryExists) {
            throw new BusinessException(400, "岗位分类 '" + employee.getCategory() + "' 不存在，请先在岗位分类管理中维护");
        }
        if (!StringUtils.hasText(employee.getPosition())) {
            throw new BusinessException(400, "岗位名称不能为空");
        }
        if (!StringUtils.hasText(employee.getOrgName())) {
            throw new BusinessException(400, "部门不能为空");
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
