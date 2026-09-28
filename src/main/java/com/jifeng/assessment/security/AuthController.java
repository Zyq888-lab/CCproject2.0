package com.jifeng.assessment.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.ApiResponse;
import com.jifeng.assessment.system.SystemParamService;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final SystemParamService systemParamService;

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request,
                                                   HttpServletRequest httpRequest) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(request.username(), request.password());
        Authentication authentication = authenticationManager.authenticate(token);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 创建Session
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

        // 反查强制改密标记——前端据此跳转改密页
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, authentication.getName()));

        Map<String, Object> result = new HashMap<>();
        result.put("username", authentication.getName());
        result.put("roles", authentication.getAuthorities().toString());
        result.put("mustChangePassword", user != null && Boolean.TRUE.equals(user.getMustChangePassword()));
        return ApiResponse.success(result);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.success(null);
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ApiResponse.error(401, "未登录");
        }
        // 反查员工工号——前端据此做打分按钮条件渲染与评分页越权拦截
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        Map<String, Object> result = new HashMap<>();
        result.put("username", auth.getName());
        result.put("employeeId", user != null ? user.getEmployeeId() : null);
        result.put("roles", auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList()));
        result.put("mustChangePassword", user != null && Boolean.TRUE.equals(user.getMustChangePassword()));
        return ApiResponse.success(result);
    }

    // 功能：修改密码——校验旧密码 + D2 强度规则，成功后清标记并注销会话强制重新登录
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest request,
                                            HttpServletRequest httpRequest) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ApiResponse.error(401, "未登录");
        }

        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, auth.getName()));
        if (user == null) {
            return ApiResponse.error(401, "未登录");
        }

        String oldPassword = request.oldPassword();
        String newPassword = request.newPassword();
        if (oldPassword == null || oldPassword.isBlank() || newPassword == null || newPassword.isBlank()) {
            return ApiResponse.error(400, "旧密码和新密码不能为空");
        }

        // 校验旧密码
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            return ApiResponse.error(400, "旧密码不正确");
        }

        // D2 强度规则：≥8位 + 至少一个字母一个数字 + 不等于旧密码 + 不等于默认初始密码（system_param 可配）
        String defaultPassword = systemParamService.getValueOrDefault("DEFAULT_INITIAL_PASSWORD", "123456");
        String strengthError = validateNewPassword(newPassword, oldPassword, defaultPassword);
        if (strengthError != null) {
            return ApiResponse.error(400, strengthError);
        }

        // 更新密码 + 清标记
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        sysUserMapper.updateById(user);

        // 注销当前会话，闭合被盗会话窗口，强制重新登录
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        return ApiResponse.success("密码已更新，请重新登录", null);
    }

    // 功能：新密码强度校验——返回错误提示，通过返回 null
    private String validateNewPassword(String newPassword, String oldPassword, String defaultPassword) {
        if (newPassword.length() < 8) {
            return "新密码长度至少8位";
        }
        boolean hasLetter = newPassword.chars().anyMatch(Character::isLetter);
        boolean hasDigit = newPassword.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return "新密码必须同时包含字母和数字";
        }
        if (newPassword.equals(oldPassword)) {
            return "新密码不能与旧密码相同";
        }
        if (newPassword.equals(defaultPassword)) {
            return "新密码不能使用默认密码";
        }
        return null;
    }

    public record LoginRequest(String username, String password) {}

    public record ChangePasswordRequest(String oldPassword, String newPassword) {}
}
