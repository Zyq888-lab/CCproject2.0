// 模块用途：用户管理业务逻辑——创建用户（密码加密+工号关联校验）、角色分配（覆盖式更新）、分页查询（关联员工姓名）
// 依赖文件：SysUserMapper.java, SysUser.java, UserRoleMapper.java, UserRole.java, EmployeeMapper.java, Employee.java
// 修改注意：角色覆盖逻辑不变，新增角色类型在 RoleType 枚举中添加
package com.jifeng.assessment.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageQuery;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.system.SystemParam;
import com.jifeng.assessment.system.SystemParamMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService extends BaseService<SysUserMapper, SysUser> {

    private final UserRoleMapper userRoleMapper;
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;
    private final SystemParamMapper systemParamMapper;

    // 功能：分页查询用户列表，关联 employee 表返回员工姓名
    public PageResult<UserDTO> listUsers(PageQuery query) {
        PageResult<SysUser> page = selectPage(query, null);
        List<String> employeeIds = page.getList().stream()
                .map(SysUser::getEmployeeId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        Map<String, String> employeeNameMap = employeeIds.isEmpty() ? Map.of()
                : employeeMapper.selectBatchIds(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Employee::getName, (a, b) -> a));

        List<UserDTO> dtoList = new ArrayList<>();
        for (SysUser user : page.getList()) {
            UserDTO dto = new UserDTO();
            dto.setUserId(user.getUserId());
            dto.setUsername(user.getUsername());
            dto.setEmployeeId(user.getEmployeeId());
            dto.setEmployeeName(employeeNameMap.getOrDefault(user.getEmployeeId(), null));
            dto.setEnabled(user.getEnabled());
            dto.setCreatedAt(user.getCreatedAt());

            List<UserRole> roles = userRoleMapper.selectList(
                    new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, user.getUserId()));
            dto.setRoles(roles.stream().map(UserRole::getRoleType).toList());

            dtoList.add(dto);
        }
        return PageResult.of(page.getTotal(), page.getPage(), page.getSize(), dtoList);
    }

    // 功能：分页查询员工账号总览——以 employee 表为底座分页，批量左连接 sys_user 回填激活状态与角色，
    //   未激活员工也出现在列表中（activated=false），与员工管理页人数一致
    public PageResult<EmployeeAccountDTO> listEmployeeAccounts(PageQuery query) {
        Page<Employee> page = employeeMapper.selectPage(
                Page.of(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<Employee>().orderByAsc(Employee::getEmployeeId));

        List<Employee> employees = page.getRecords();
        List<String> employeeIds = employees.stream().map(Employee::getEmployeeId).toList();

        // 批量查已激活账号，按 employeeId 建索引
        Map<String, SysUser> userByEmployeeId = employeeIds.isEmpty() ? Map.of()
                : baseMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getEmployeeId, employeeIds))
                .stream().collect(Collectors.toMap(SysUser::getEmployeeId, u -> u, (a, b) -> a));

        // 批量查角色，按 userId 建索引
        List<String> userIds = userByEmployeeId.values().stream().map(SysUser::getUserId).toList();
        Map<String, List<String>> rolesByUserId = userIds.isEmpty() ? Map.of()
                : userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .in(UserRole::getUserId, userIds))
                .stream().collect(Collectors.groupingBy(UserRole::getUserId,
                        Collectors.mapping(UserRole::getRoleType, Collectors.toList())));

        List<EmployeeAccountDTO> dtoList = new ArrayList<>();
        for (Employee e : employees) {
            EmployeeAccountDTO dto = new EmployeeAccountDTO();
            dto.setEmployeeId(e.getEmployeeId());
            dto.setName(e.getName());
            dto.setEmail(e.getEmail());
            dto.setCategory(e.getCategory());
            dto.setPosition(e.getPosition());
            dto.setOrgName(e.getOrgName());
            dto.setDirectLeaderId(e.getDirectLeaderId());
            dto.setStatus(e.getStatus());

            SysUser user = userByEmployeeId.get(e.getEmployeeId());
            if (user != null) {
                dto.setActivated(true);
                dto.setUserId(user.getUserId());
                dto.setUsername(user.getUsername());
                dto.setEnabled(user.getEnabled());
                dto.setRoles(rolesByUserId.getOrDefault(user.getUserId(), List.of()));
            } else {
                dto.setActivated(false);
                dto.setRoles(List.of());
            }
            dtoList.add(dto);
        }
        return PageResult.of(page.getTotal(), query.getPage(), query.getSize(), dtoList);
    }

    // 功能：创建系统用户——校验用户名非空、employeeId存在且未被其他用户关联、密码bcrypt(12)加密
    @Transactional
    public UserDTO createUser(String employeeId, String username, String password) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(400, "用户名不能为空");
        }
        if (!StringUtils.hasText(password)) {
            throw new BusinessException(400, "密码不能为空");
        }
        if (!StringUtils.hasText(employeeId)) {
            throw new BusinessException(400, "关联员工工号不能为空");
        }

        // 校验员工存在
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new BusinessException(400, "员工工号不存在: " + employeeId);
        }

        // 校验该员工未被其他用户关联
        LambdaQueryWrapper<SysUser> existWrapper = new LambdaQueryWrapper<>();
        existWrapper.eq(SysUser::getEmployeeId, employeeId);
        if (baseMapper.selectCount(existWrapper) > 0) {
            throw new BusinessException(409, "该员工已关联系统用户，无法重复创建");
        }

        // 生成 userId
        String nextUserId = generateNextUserId();

        SysUser user = new SysUser();
        user.setUserId(nextUserId);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmployeeId(employeeId);
        user.setEnabled(true);

        try {
            baseMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "用户名已存在: " + username);
        }

        UserDTO dto = new UserDTO();
        dto.setUserId(user.getUserId());
        dto.setUsername(user.getUsername());
        dto.setEmployeeId(user.getEmployeeId());
        dto.setEmployeeName(employee.getName());
        dto.setEnabled(user.getEnabled());
        dto.setRoles(List.of());
        return dto;
    }

    // 功能：一键激活账号——username=工号、密码=bcrypt(默认初始密码)、must_change_password=true、写入 user_role
    // 说明：复用 createUser 的员工存在/未关联校验；仅新激活账号置 true，不回溯已有账号（D3）
    @Transactional
    public UserDTO activate(String employeeId, List<String> roleTypes) {
        if (!StringUtils.hasText(employeeId)) {
            throw new BusinessException(400, "关联员工工号不能为空");
        }
        if (roleTypes == null || roleTypes.isEmpty()) {
            throw new BusinessException(400, "请至少选择一个角色");
        }

        // 校验员工存在
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new BusinessException(400, "员工工号不存在: " + employeeId);
        }

        // 校验该员工未被其他用户关联
        LambdaQueryWrapper<SysUser> existWrapper = new LambdaQueryWrapper<>();
        existWrapper.eq(SysUser::getEmployeeId, employeeId);
        if (baseMapper.selectCount(existWrapper) > 0) {
            throw new BusinessException(409, "该员工已关联系统用户，无法重复激活");
        }

        // 校验角色代码合法性
        for (String roleType : roleTypes) {
            if (RoleType.fromCode(roleType) == null) {
                throw new BusinessException(400, "无效的角色类型: " + roleType);
            }
        }

        // username = 工号；工号可能已被其他用户用作 username，先查重
        String username = employeeId;
        LambdaQueryWrapper<SysUser> usernameWrapper = new LambdaQueryWrapper<>();
        usernameWrapper.eq(SysUser::getUsername, username);
        if (baseMapper.selectCount(usernameWrapper) > 0) {
            throw new BusinessException(409, "用户名已存在: " + username);
        }

        // 默认初始密码（系统参数可改，缺失回退 '123456'）
        String initialPassword = getDefaultInitialPassword();

        String nextUserId = generateNextUserId();

        SysUser user = new SysUser();
        user.setUserId(nextUserId);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setEmployeeId(employeeId);
        user.setEnabled(true);
        user.setMustChangePassword(true);

        try {
            baseMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "用户名已存在: " + username);
        }

        // 写入角色（去重）
        List<String> distinctRoleTypes = roleTypes.stream().distinct().toList();
        for (String roleType : distinctRoleTypes) {
            UserRole role = new UserRole();
            role.setUserId(nextUserId);
            role.setRoleType(roleType);
            userRoleMapper.insert(role);
        }

        UserDTO dto = new UserDTO();
        dto.setUserId(user.getUserId());
        dto.setUsername(user.getUsername());
        dto.setEmployeeId(user.getEmployeeId());
        dto.setEmployeeName(employee.getName());
        dto.setEnabled(user.getEnabled());
        dto.setRoles(distinctRoleTypes);
        return dto;
    }

    // 功能：批量一键激活——逐个复用 activate 单条校验，已激活跳过、失败记明细，单事务原子提交
    @Transactional
    public BatchActivateResult activateBatch(List<String> employeeIds, List<String> roleTypes) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            throw new BusinessException(400, "请至少选择一个员工");
        }
        if (roleTypes == null || roleTypes.isEmpty()) {
            throw new BusinessException(400, "请至少选择一个角色");
        }
        // 角色合法性预校验一次，避免逐行报错
        for (String roleType : roleTypes) {
            if (RoleType.fromCode(roleType) == null) {
                throw new BusinessException(400, "无效的角色类型: " + roleType);
            }
        }

        List<String> distinctIds = employeeIds.stream()
                .filter(StringUtils::hasText).distinct().toList();

        List<BatchActivateResult.Item> items = new ArrayList<>();
        int successCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for (String employeeId : distinctIds) {
            // 已激活则跳过（幂等：重复勾选已激活员工不报错）
            LambdaQueryWrapper<SysUser> existWrapper = new LambdaQueryWrapper<>();
            existWrapper.eq(SysUser::getEmployeeId, employeeId);
            if (baseMapper.selectCount(existWrapper) > 0) {
                skippedCount++;
                items.add(new BatchActivateResult.Item(employeeId, false, true, "已激活，跳过", null, null));
                continue;
            }
            try {
                UserDTO dto = activate(employeeId, roleTypes);
                successCount++;
                items.add(new BatchActivateResult.Item(employeeId, true, false, "激活成功", dto.getUserId(), dto.getUsername()));
            } catch (BusinessException e) {
                failedCount++;
                items.add(new BatchActivateResult.Item(employeeId, false, false, e.getMessage(), null, null));
            }
        }
        return new BatchActivateResult(distinctIds.size(), successCount, skippedCount, failedCount, items);
    }

    // 功能：读取默认初始密码参数——系统参数缺失或为空时回退 '123456'
    private String getDefaultInitialPassword() {
        SystemParam param = systemParamMapper.selectOne(
                new LambdaQueryWrapper<SystemParam>()
                        .eq(SystemParam::getParamKey, "DEFAULT_INITIAL_PASSWORD"));
        if (param != null && StringUtils.hasText(param.getParamValue())) {
            return param.getParamValue();
        }
        return "123456";
    }

    // 功能：覆盖式更新用户角色——物理删除原有角色后插入去重后的新角色列表
    @Transactional
    public List<String> updateUserRoles(String userId, List<String> roleTypes) {
        SysUser user = baseMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在: " + userId);
        }

        // 校验角色代码合法性
        for (String roleType : roleTypes) {
            if (RoleType.fromCode(roleType) == null) {
                throw new BusinessException(400, "无效的角色类型: " + roleType);
            }
        }

        // 去重：同一角色只保留一条，避免插入触发 uk_user_role 唯一约束
        List<String> distinctRoleTypes = roleTypes.stream().distinct().toList();

        // 操作人自身保护：禁止移除自己的管理员角色，避免把系统锁死
        String currentUserId = getCurrentUserId();
        if (userId.equals(currentUserId) && isCurrentUserAdmin() && !distinctRoleTypes.contains("ADMIN")) {
            throw new BusinessException(400, "不能移除自己的管理员角色");
        }

        // 物理删除用户原有角色（绕过全局逻辑删除，避免 deleted=1 残留行积累导致唯一约束冲突）
        userRoleMapper.deletePhysicallyByUserId(userId);

        // 插入新角色列表
        for (String roleType : distinctRoleTypes) {
            UserRole role = new UserRole();
            role.setUserId(userId);
            role.setRoleType(roleType);
            userRoleMapper.insert(role);
        }

        return distinctRoleTypes;
    }

    // 功能：根据 userId 查询用户的角色列表
    public List<String> getUserRoles(String userId) {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getUserId, userId);
        return userRoleMapper.selectList(wrapper).stream()
                .map(UserRole::getRoleType)
                .toList();
    }

    // 功能：生成下一个用户ID（格式 U + 自增数字，如 U002, U003...）
    private String generateNextUserId() {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SysUser::getUserId);
        wrapper.last("LIMIT 1");
        SysUser lastUser = baseMapper.selectOne(wrapper);
        if (lastUser == null) {
            return "U002";
        }
        String lastId = lastUser.getUserId();
        int num = Integer.parseInt(lastId.substring(1));
        return String.format("U%03d", num + 1);
    }

    // 功能：从安全上下文反查当前登录用户的 userId——用于角色变更自身保护
    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        SysUser user = baseMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        return user != null ? user.getUserId() : null;
    }

    // 功能：判断当前登录用户是否 ADMIN——角色变更自身保护用
    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // 功能：批量激活结果——逐员工成败明细（success=false 且 skipped=true 表示已激活跳过）
    public record BatchActivateResult(
            int total, int successCount, int skippedCount, int failedCount, List<Item> items) {
        public record Item(String employeeId, boolean success, boolean skipped,
                           String message, String userId, String username) {}
    }
}
