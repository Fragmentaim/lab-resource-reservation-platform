package com.fragment.labbooking.common.auth;

import com.fragment.labbooking.common.exception.BusinessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

public final class UserContext {

    private UserContext() {
    }

    public static void set(LoginUser loginUser) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated(loginUser));
        SecurityContextHolder.setContext(context);
    }

    public static LoginUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser
                ? loginUser
                : null;
    }

    public static LoginUser requireUser() {
        LoginUser loginUser = get();
        if (loginUser == null) {
            throw new BusinessException(401, "未登录或登录已失效");
        }
        return loginUser;
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }

    public static Authentication authenticated(LoginUser loginUser) {
        List<SimpleGrantedAuthority> authorities = loginUser == null || loginUser.getRole() == null
                ? List.of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + loginUser.getRole()));
        return UsernamePasswordAuthenticationToken.authenticated(loginUser, null, authorities);
    }
}
