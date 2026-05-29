package com.fragment.labbooking.common.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.constants.UserRoleConstants;
import com.fragment.labbooking.common.constants.UserStatusConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.service.SysUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Autowired
    private SysUserService sysUserService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(401, "未登录或登录已失效");
        }

        String token = authorization.substring(7);
        LoginUser tokenUser = jwtTokenUtil.parseToken(token);

        SysUser sysUser = sysUserService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, tokenUser.getId())
                .last("limit 1"));
        if (sysUser == null || !UserStatusConstants.ACTIVE.equals(sysUser.getStatus())) {
            throw new BusinessException(401, "未登录或登录已失效");
        }

        LoginUser currentUser = new LoginUser(
                sysUser.getId(),
                sysUser.getUsername(),
                sysUser.getNickname(),
                sysUser.getRole(),
                sysUser.getPhone()
        );
        UserContext.set(currentUser);

        boolean adminOnly = handlerMethod.hasMethodAnnotation(AdminOnly.class)
                || handlerMethod.getBeanType().isAnnotationPresent(AdminOnly.class);
        if (adminOnly && !UserRoleConstants.ADMIN.equals(currentUser.getRole())) {
            throw new BusinessException(403, "无权限访问");
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
