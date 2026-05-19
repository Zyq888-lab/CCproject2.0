package com.jifeng.assessment.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import com.jifeng.assessment.user.UserRole;
import com.jifeng.assessment.user.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        createAdminUserIfNotExists();
    }

    private void createAdminUserIfNotExists() {
        if (sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin")) > 0) {
            return;
        }

        // 创建 ADMIN 员工记录
        if (employeeMapper.selectById("ADMIN") == null) {
            Employee adminEmp = new Employee();
            adminEmp.setEmployeeId("ADMIN");
            adminEmp.setName("系统管理员");
            adminEmp.setEmail("admin@jifeng.com");
            adminEmp.setCategory("管理类");
            adminEmp.setPosition("系统管理员");
            adminEmp.setOrgName("信息部");
            adminEmp.setStatus("ACTIVE");
            employeeMapper.insert(adminEmp);
        }

        // 创建 ADMIN 用户账号
        SysUser admin = new SysUser();
        admin.setUserId("U001");
        admin.setUsername("admin");
        admin.setPasswordHash(passwordEncoder.encode("admin123"));
        admin.setEmployeeId("ADMIN");
        admin.setEnabled(true);
        sysUserMapper.insert(admin);

        // 分配 ADMIN 角色
        UserRole role = new UserRole();
        role.setUserId("U001");
        role.setRoleType("ADMIN");
        userRoleMapper.insert(role);

        log.info("初始ADMIN账号已创建 — 用户名: admin, 密码: admin123");
    }
}
