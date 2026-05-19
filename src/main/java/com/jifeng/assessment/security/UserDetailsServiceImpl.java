package com.jifeng.assessment.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.jifeng.assessment.user.SysUser;
import com.jifeng.assessment.user.UserRole;
import com.jifeng.assessment.user.SysUserMapper;
import com.jifeng.assessment.user.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser sysUser = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));

        if (sysUser == null) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        List<UserRole> roles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, sysUser.getUserId()));
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.getRoleType()))
                .toList();

        return User.builder()
                .username(sysUser.getUsername())
                .password(sysUser.getPasswordHash())
                .authorities(authorities)
                .disabled(!sysUser.getEnabled())
                .build();
    }
}
