package com.fragment.labbooking.common.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.constants.UserStatusConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.service.SysUserService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class JwtAuthenticationProvider implements AuthenticationProvider {

    private final JwtTokenUtil jwtTokenUtil;
    private final SysUserService sysUserService;

    public JwtAuthenticationProvider(JwtTokenUtil jwtTokenUtil, SysUserService sysUserService) {
        this.jwtTokenUtil = jwtTokenUtil;
        this.sysUserService = sysUserService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        try {
            LoginUser tokenUser = jwtTokenUtil.parseToken((String) authentication.getCredentials());
            SysUser sysUser = sysUserService.getOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getId, tokenUser.getId())
                    .last("limit 1"));
            if (sysUser == null) {
                throw new BadCredentialsException("User does not exist");
            }
            if (!UserStatusConstants.ACTIVE.equals(sysUser.getStatus())) {
                throw new DisabledException("User is disabled");
            }

            LoginUser currentUser = new LoginUser(sysUser.getId(), sysUser.getUsername(),
                    sysUser.getNickname(), sysUser.getRole(), sysUser.getPhone());
            return UserContext.authenticated(currentUser);
        } catch (BusinessException exception) {
            throw new BadCredentialsException("Invalid JWT", exception);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return JwtBearerToken.class.isAssignableFrom(authentication);
    }
}
