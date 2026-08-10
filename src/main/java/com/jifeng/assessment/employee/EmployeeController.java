// 模块用途：员工管理REST接口——提供员工CRUD的分页列表、详情、新增、编辑、删除API
// 依赖文件：EmployeeService.java, EmployeeDTO.java, Employee.java, BaseController.java
// 修改注意：接口路径统一以 /api/v1/employees 开头，返回值统一用 ApiResponse 包装
package com.jifeng.assessment.employee;

import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.common.BaseController;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/employees")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class EmployeeController extends BaseController {

    private final EmployeeService employeeService;

    // 功能：分页查询员工列表，支持关键字搜索（姓名/工号）、岗位分类、状态筛选
    @GetMapping
    public ApiResponse<PageResult<EmployeeDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String directLeaderId,
            @RequestParam(required = false) String orgName) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setSize(size);
        query.setKeyword(keyword);
        return ok(employeeService.listEmployees(query, category, status, directLeaderId, orgName));
    }

    // 功能：根据工号获取单个员工详情，不存在返回 404
    @GetMapping("/{employeeId}")
    public ApiResponse<EmployeeDTO> get(@PathVariable String employeeId) {
        EmployeeDTO dto = employeeService.getEmployee(employeeId);
        if (dto == null) {
            return fail(404, "员工不存在: " + employeeId);
        }
        return ok(dto);
    }

    // 功能：新增员工——校验工号不重复、必填字段完整、直属上级存在，通过后返回员工信息
    @PostMapping
    public ApiResponse<EmployeeDTO> create(@Valid @RequestBody Employee employee) {
        return ok(employeeService.createEmployee(employee));
    }

    // 功能：更新员工信息——修改岗位、状态等字段，校验直属上级工号存在
    @PutMapping("/{employeeId}")
    public ApiResponse<EmployeeDTO> update(@PathVariable String employeeId,
                                           @RequestBody Employee employee) {
        employee.setEmployeeId(employeeId);
        return ok(employeeService.updateEmployee(employee));
    }

    // 功能：删除员工——逻辑删除（MyBatis-Plus自动将deleteById转为UPDATE SET deleted=1），删除前检查项目角色分配引用
    @DeleteMapping("/{employeeId}")
    public ApiResponse<Void> delete(@PathVariable String employeeId) {
        employeeService.deleteEmployee(employeeId);
        return ok("已删除", null);
    }

    // 功能：批量导入员工——接收员工列表，逐条校验入库，返回成功/失败明细
    @PostMapping("/import")
    public ApiResponse<EmployeeService.ImportResult> importEmployees(@RequestBody List<Employee> employees) {
        return ok(employeeService.importEmployees(employees));
    }
}
