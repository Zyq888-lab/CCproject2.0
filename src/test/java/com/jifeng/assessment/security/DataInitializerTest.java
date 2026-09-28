package com.jifeng.assessment.security;

import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.SysUserMapper;
import com.jifeng.assessment.user.UserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {
    @Mock SysUserMapper userMapper;
    @Mock UserRoleMapper roleMapper;
    @Mock EmployeeMapper employeeMapper;
    @Mock PasswordEncoder encoder;
    @Mock Environment environment;
    @InjectMocks DataInitializer initializer;

    @Test
    void missingBootstrapPasswordFailsBeforeCreatingAnyAccount() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        ReflectionTestUtils.setField(initializer, "bootstrapPassword", "");
        assertThrows(IllegalStateException.class, () -> initializer.run(null));
        verify(userMapper, never()).insert(any(SysUser.class));
        verifyNoInteractions(employeeMapper, roleMapper);
    }

    @Test
    void weakBootstrapPasswordIsRejected() {
        ReflectionTestUtils.setField(initializer, "bootstrapPassword", "weak");
        assertThrows(IllegalStateException.class, () -> initializer.run(null));
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void newAdminUsesProvidedPasswordAndMustChangeIt() {
        ReflectionTestUtils.setField(initializer, "bootstrapPassword", "UnitTest-Strong-123!");
        when(encoder.encode("UnitTest-Strong-123!")).thenReturn("encoded-bootstrap");
        initializer.run(null);
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("encoded-bootstrap", captor.getValue().getPasswordHash());
        assertTrue(captor.getValue().getMustChangePassword());
        verify(roleMapper).insert(any(com.jifeng.assessment.user.UserRole.class));
    }

    @Test
    void existingDefaultPasswordIsRotatedOnce() {
        SysUser existing = new SysUser();
        existing.setPasswordHash("legacy-default-hash");
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(encoder.matches("admin123", "legacy-default-hash")).thenReturn(true);
        when(encoder.encode("UnitTest-Strong-123!")).thenReturn("rotated-hash");
        when(userMapper.updateById(existing)).thenReturn(1);
        ReflectionTestUtils.setField(initializer, "bootstrapPassword", "UnitTest-Strong-123!");
        initializer.run(null);
        assertEquals("rotated-hash", existing.getPasswordHash());
        assertTrue(existing.getMustChangePassword());
        verifyNoInteractions(employeeMapper, roleMapper);
    }

    @Test
    void existingNonDefaultPasswordIsPreserved() {
        SysUser existing = new SysUser();
        existing.setPasswordHash("already-secure-hash");
        when(userMapper.selectOne(any())).thenReturn(existing);
        initializer.run(null);
        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(encoder, never()).encode(any());
    }
}
