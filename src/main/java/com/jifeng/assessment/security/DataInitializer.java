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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Value("${ADMIN_BOOTSTRAP_PASSWORD:}")
    private String bootstrapPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createAdminUserIfNotExists();
    }

    private void createAdminUserIfNotExists() {
        SysUser existing = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin"));
        if (existing != null) {
            if (passwordEncoder.matches("admin123", existing.getPasswordHash())) {
                String password = requireBootstrapPassword();
                existing.setPasswordHash(passwordEncoder.encode(password));
                existing.setMustChangePassword(true);
                if (sysUserMapper.updateById(existing) != 1) {
                    throw new IllegalStateException("管理员口令轮换失败，拒绝继续启动");
                }
                log.warn("检测到旧默认管理员口令，已轮换并要求首次登录改密");
            }
            return;
        }
        String password = requireBootstrapPassword();

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
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setEmployeeId("ADMIN");
        admin.setEnabled(true);
        admin.setMustChangePassword(true);
        sysUserMapper.insert(admin);

        // 分配 ADMIN 角色
        UserRole role = new UserRole();
        role.setUserId("U001");
        role.setRoleType("ADMIN");
        userRoleMapper.insert(role);

        log.info("初始ADMIN账号已创建；请使用配置的引导口令登录并立即改密");
    }

    private String requireBootstrapPassword() {
        String password = bootstrapPassword;
        if (!StringUtils.hasText(password) && Arrays.asList(environment.getActiveProfiles()).contains("test")) {
            password = UUID.randomUUID() + "Aa1!";
        }
        if (!StringUtils.hasText(password) || password.length() < 12 || password.length() > 128
                || !password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*")
                || !password.matches(".*[0-9].*") || !password.matches(".*[^A-Za-z0-9].*")) {
            throw new IllegalStateException("首次创建或轮换管理员账号时，必须配置强口令 ADMIN_BOOTSTRAP_PASSWORD");
        }
        return password;
    }
}
