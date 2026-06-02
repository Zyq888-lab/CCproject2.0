// 模块用途：员工数据自定义校验——工号格式、邮箱格式等业务规则校验，不依赖数据库查询
// 依赖文件：Employee.java
// 修改注意：校验方法只做纯字段格式检查，存在性校验放在 EmployeeService 中
package com.jifeng.assessment.employee;

import com.jifeng.assessment.common.BusinessException;
import org.springframework.util.StringUtils;

public final class EmployeeValidator {

    private EmployeeValidator() {}

    // 工号格式：字母数字下划线，3-32位
    private static final String EMPLOYEE_ID_PATTERN = "^[A-Za-z0-9_]{3,32}$";

    // 功能：校验工号格式是否符合字母数字下划线3-32位的规则
    public static void validateEmployeeId(String employeeId) {
        if (!StringUtils.hasText(employeeId)) {
            throw new BusinessException(400, "工号不能为空");
        }
        if (!employeeId.matches(EMPLOYEE_ID_PATTERN)) {
            throw new BusinessException(400, "工号格式不正确：仅支持字母、数字、下划线，3-32位");
        }
    }

    // 功能：校验邮箱格式是否合法（需包含@及域名后缀，长度不超过128字符）
    public static void validateEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw new BusinessException(400, "邮箱不能为空");
        }
        if (email.length() > 128) {
            throw new BusinessException(400, "邮箱地址过长，最多128个字符");
        }
        if (!email.matches("^[^@]+@[^@]+\\.[^@]+$")) {
            throw new BusinessException(400, "邮箱格式不正确");
        }
    }

    private static final java.util.Map<String, String> STATUS_MAP = java.util.Map.of(
        "在职", "ACTIVE",
        "离职", "INACTIVE"
    );

    public static void validateStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return;
        }
        if (STATUS_MAP.containsKey(status)) {
            return;
        }
        if (!status.equals("ACTIVE") && !status.equals("INACTIVE")) {
            throw new BusinessException(400, "员工状态无效，仅支持 ACTIVE、INACTIVE、在职、离职");
        }
    }

    public static String mapStatus(String status) {
        if (status == null) return null;
        return STATUS_MAP.getOrDefault(status, status);
    }
}
