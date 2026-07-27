package com.fragment.labbooking.common.auth;

import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.service.SysUserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationProviderTest {

    @Test
    void shouldRefreshPrincipalFromDatabaseAndAuthenticateActiveUser() {
        JwtTokenUtil jwtTokenUtil = mock(JwtTokenUtil.class);
        SysUserService sysUserService = mock(SysUserService.class);
        when(jwtTokenUtil.parseToken("jwt")).thenReturn(
                new LoginUser(5L, "old-name", "Old", "USER", null));
        SysUser user = user("new-name", "ADMIN", "ACTIVE");
        when(sysUserService.getOne(any())).thenReturn(user);
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(jwtTokenUtil, sysUserService);

        Authentication result = provider.authenticate(new JwtBearerToken("jwt"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isInstanceOf(LoginUser.class);
        assertThat(((LoginUser) result.getPrincipal()).getUsername()).isEqualTo("new-name");
        assertThat(result.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void shouldRejectDisabledUser() {
        JwtTokenUtil jwtTokenUtil = mock(JwtTokenUtil.class);
        SysUserService sysUserService = mock(SysUserService.class);
        when(jwtTokenUtil.parseToken("jwt")).thenReturn(
                new LoginUser(5L, "alice", "Alice", "USER", null));
        when(sysUserService.getOne(any())).thenReturn(user("alice", "USER", "LOCKED"));
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(jwtTokenUtil, sysUserService);

        assertThatThrownBy(() -> provider.authenticate(new JwtBearerToken("jwt")))
                .isInstanceOf(DisabledException.class);
    }

    private SysUser user(String username, String role, String status) {
        SysUser user = new SysUser();
        user.setId(5L);
        user.setUsername(username);
        user.setNickname("Alice");
        user.setRole(role);
        user.setPhone("13800000000");
        user.setStatus(status);
        return user;
    }
}
