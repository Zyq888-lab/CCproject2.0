package com.jifeng.assessment.security;

import com.jifeng.assessment.system.SystemParamService;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerPasswordChangeTest {
    @Mock AuthenticationManager authenticationManager;
    @Mock SysUserMapper userMapper;
    @Mock PasswordEncoder encoder;
    @Mock SystemParamService params;
    @InjectMocks AuthController controller;
    private SysUser user;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        user = new SysUser();
        user.setPasswordHash("synthetic-old-hash");
        user.setMustChangePassword(true);
        request = new MockHttpServletRequest();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedAndAnonymousRequestsDoNotChangePasswords() {
        assertEquals(401, change("SyntheticOld1", "SyntheticNew2").getCode());
        authenticate("anonymousUser");
        assertEquals(401, change("SyntheticOld1", "SyntheticNew2").getCode());
        verifyNoInteractions(userMapper, encoder, params);
    }

    @Test
    void missingAuthenticatedAccountReturnsUnauthorized() {
        authenticate("password-test-user");
        assertEquals(401, change("SyntheticOld1", "SyntheticNew2").getCode());
        verifyNoInteractions(encoder, params);
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void blankInputCannotUpdatePasswordOrInvalidateSession() {
        authenticate("password-test-user");
        when(userMapper.selectOne(any())).thenReturn(user);
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        assertEquals(400, change(null, "SyntheticNew2").getCode());
        assertEquals(400, change("SyntheticOld1", " ").getCode());
        assertFalse(session.isInvalid());
        verifyNoInteractions(encoder, params);
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void incorrectOldPasswordPreservesPasswordFlagAndSession() {
        authenticate("password-test-user");
        when(userMapper.selectOne(any())).thenReturn(user);
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        assertEquals(400, change("SyntheticWrong1", "SyntheticNew2").getCode());
        assertEquals("synthetic-old-hash", user.getPasswordHash());
        assertTrue(user.getMustChangePassword());
        assertFalse(session.isInvalid());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void weakUnchangedAndDefaultNewPasswordsAreRejectedBeforeWrite() {
        allowOldPassword();
        when(params.getValueOrDefault("DEFAULT_INITIAL_PASSWORD", "123456"))
                .thenReturn("SyntheticDefault3");
        for (String newPassword : List.of("Ab1", "OnlyLetters", "123456789", "SyntheticOld1", "SyntheticDefault3")) {
            assertEquals(400, change("SyntheticOld1", newPassword).getCode());
        }
        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(encoder, never()).encode(any());
        assertTrue(user.getMustChangePassword());
    }

    @Test
    void successfulChangeClearsFlagAndInvalidatesAuthenticatedSession() {
        allowOldPassword();
        when(params.getValueOrDefault("DEFAULT_INITIAL_PASSWORD", "123456")).thenReturn("SyntheticDefault3");
        when(encoder.encode("SyntheticNew2")).thenReturn("synthetic-new-hash");
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        assertEquals(200, change("SyntheticOld1", "SyntheticNew2").getCode());
        assertEquals("synthetic-new-hash", user.getPasswordHash());
        assertFalse(user.getMustChangePassword());
        verify(userMapper).updateById(user);
        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void successfulChangeWithoutSessionStillClearsAuthentication() {
        allowOldPassword();
        when(params.getValueOrDefault("DEFAULT_INITIAL_PASSWORD", "123456")).thenReturn("SyntheticDefault3");
        when(encoder.encode("SyntheticNew2")).thenReturn("synthetic-new-hash");
        assertEquals(200, change("SyntheticOld1", "SyntheticNew2").getCode());
        assertNull(request.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(userMapper).updateById(user);
    }

    private com.jifeng.assessment.common.ApiResponse<Void> change(String oldPassword, String newPassword) {
        return controller.changePassword(new AuthController.ChangePasswordRequest(oldPassword, newPassword), request);
    }

    private void allowOldPassword() {
        authenticate("password-test-user");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(encoder.matches("SyntheticOld1", "synthetic-old-hash")).thenReturn(true);
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }
}
