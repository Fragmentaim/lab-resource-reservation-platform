package com.fragment.labbooking.common.auth;

import com.fragment.labbooking.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenUtil {

    public static final String TOKEN_TYPE = "Bearer";

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expire-hours}")
    private long expireHours;

    public String generateToken(LoginUser loginUser) {
        Instant now = Instant.now();
        Instant expireAt = now.plus(Duration.ofHours(expireHours));

        return Jwts.builder()
                .subject(String.valueOf(loginUser.getId()))
                .claim("username", loginUser.getUsername())
                .claim("role", loginUser.getRole())
                .claim("nickname", loginUser.getNickname())
                .claim("phone", loginUser.getPhone())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expireAt))
                .signWith(buildKey())
                .compact();
    }

    public LoginUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(buildKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new LoginUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get("username", String.class),
                    claims.get("nickname", String.class),
                    claims.get("role", String.class),
                    claims.get("phone", String.class)
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(401, "未登录或登录已失效");
        }
    }

    public long getExpireSeconds() {
        return Duration.ofHours(expireHours).getSeconds();
    }

    private SecretKey buildKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
