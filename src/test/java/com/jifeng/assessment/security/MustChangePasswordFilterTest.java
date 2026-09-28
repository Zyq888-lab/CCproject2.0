package com.jifeng.assessment.security;

import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MustChangePasswordFilterTest {
    @Mock SysUserMapper userMapper;
    @Mock FilterChain chain;
    @InjectMocks MustChangePasswordFilter filter;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedRequestsAreLeftToAuthorizationChain() throws Exception {
        MockHttpServletRequest request = request("/api/v1/periods");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(userMapper);
    }

    @Test
    void anonymousRequestsAreLeftToAuthorizationChain() throws Exception {
        authenticate("anonymousUser");
        MockHttpServletRequest request = request("/api/v1/periods");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(userMapper);
    }

    @Test
    void passwordChangeLogoutMeAndStaticResourcesRemainAccessible() throws Exception {
        authenticate("filter-test-user");
        for (String path : List.of("/api/v1/auth/change-password", "/api/v1/auth/logout",
                "/api/v1/auth/me", "/", "/index.html", "/static/app.js", "/assets/app.js")) {
            MockHttpServletRequest request = request(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }
        verifyNoInteractions(userMapper);
    }

    @Test
    void forcedPasswordChangeBlocksBusinessRequestsAndWhitelistPrefixes() throws Exception {
        authenticate("filter-test-user");
        SysUser user = new SysUser();
        user.setMustChangePassword(true);
        when(userMapper.selectOne(any())).thenReturn(user);
        for (String path : List.of("/api/v1/periods", "/api/v1/auth/me/private")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(path), response, chain);
            assertEquals(403, response.getStatus());
            assertTrue(response.getContentAsString().contains("首次登录须先修改密码"));
            assertEquals("application/json;charset=UTF-8", response.getContentType());
        }
        verifyNoInteractions(chain);
    }

    @Test
    void latestDatabaseFlagAllowsNextRequestAfterPasswordChange() throws Exception {
        authenticate("filter-test-user");
        SysUser before = new SysUser();
        before.setMustChangePassword(true);
        SysUser after = new SysUser();
        after.setMustChangePassword(false);
        when(userMapper.selectOne(any())).thenReturn(before, after);
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/periods"), blocked, chain);
        assertEquals(403, blocked.getStatus());
        MockHttpServletRequest allowedRequest = request("/api/v1/periods");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(allowedRequest, allowedResponse, chain);
        verify(chain).doFilter(allowedRequest, allowedResponse);
        verify(userMapper, times(2)).selectOne(any());
    }

    @Test
    void databaseFailureBlocksBusinessRequest() throws Exception {
        authenticate("filter-test-user");
        when(userMapper.selectOne(any())).thenThrow(new IllegalStateException("synthetic read failure"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/periods"), response, chain);
        assertEquals(403, response.getStatus());
        verifyNoInteractions(chain);
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }
}
