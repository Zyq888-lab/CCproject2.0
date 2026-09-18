// 模块用途：强制改密硬门——已认证且 must_change_password=true 的用户，除白名单外一律 403
// 依赖文件：SysUserMapper.java, SysUser.java, SecurityConfig.java
// 修改注意：每请求按 username 重查 sys_user.must_change_password（不用登录时 principal 缓存），DB 读异常 fail-closed
package com.jifeng.assessment.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private final SysUserMapper sysUserMapper;

    // 功能：白名单——改密、登出、me 放行，其余业务接口一律拦截
    private static final Set<String> WHITELIST = Set.of(
            "/api/v1/auth/change-password",
            "/api/v1/auth/logout",
            "/api/v1/auth/me"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 未登录/匿名请求放行——由 Spring Security 授权链负责 401/放行
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (WHITELIST.contains(path) || isStaticResource(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 每请求按 username 重查 DB 取最新标记，不信任登录时的 principal 缓存
        String username = auth.getName();
        boolean mustChange;
        try {
            SysUser user = sysUserMapper.selectOne(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
            mustChange = user != null && Boolean.TRUE.equals(user.getMustChangePassword());
        } catch (Exception e) {
            // fail-closed：DB 读异常按 403 处理，宁可误伤也不放过
            log.error("查询强制改密标记失败, username={}", username, e);
            write403(response);
            return;
        }

        if (mustChange) {
            write403(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    // 功能：静态资源路径判断——前端入口与静态资源放行
    private boolean isStaticResource(String path) {
        return path.equals("/") || path.equals("/index.html")
                || path.startsWith("/static/") || path.startsWith("/assets/");
    }

    // 功能：返回 403 JSON——与 SecurityConfig 的 accessDeniedHandler 格式一致
    private void write403(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"首次登录须先修改密码\",\"data\":null}");
    }
}
