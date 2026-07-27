package com.fragment.labbooking.common.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldAuthenticateActiveUserAndExposeRoleAuthority() throws Exception {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        RestAuthenticationEntryPoint entryPoint = mock(RestAuthenticationEntryPoint.class);
        FilterChain chain = mock(FilterChain.class);
        LoginUser currentUser = new LoginUser(9L, "alice", "Alice", "USER", "13800000000");
        when(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any(JwtBearerToken.class)))
                .thenReturn(UserContext.authenticated(currentUser));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(authenticationManager, entryPoint);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resources");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(UserContext.requireUser().getId()).isEqualTo(9L);
        assertThat(UserContext.authenticated(UserContext.requireUser()).getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        verify(chain).doFilter(request, response);
        verifyNoInteractions(entryPoint);
    }

    @Test
    void shouldLeaveMissingCredentialsForSecurityChainToReject() throws Exception {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        RestAuthenticationEntryPoint entryPoint = mock(RestAuthenticationEntryPoint.class);
        FilterChain chain = mock(FilterChain.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(authenticationManager, entryPoint);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resources");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(UserContext.get()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(authenticationManager, entryPoint);
    }
}
