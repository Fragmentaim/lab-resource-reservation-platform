package com.fragment.labbooking.common.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.io.Serial;
import java.util.Collections;

public final class JwtBearerToken extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String token;

    public JwtBearerToken(String token) {
        super(Collections.emptyList());
        this.token = token;
        setAuthenticated(false);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public Object getPrincipal() {
        return token;
    }
}
