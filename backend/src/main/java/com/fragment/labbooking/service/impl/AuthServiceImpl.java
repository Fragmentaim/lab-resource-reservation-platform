package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.auth.JwtTokenUtil;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.constants.UserRoleConstants;
import com.fragment.labbooking.common.constants.UserStatusConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.dto.AuthLoginDTO;
import com.fragment.labbooking.dto.AuthRegisterDTO;
import com.fragment.labbooking.dto.ChangePasswordDTO;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.service.AuthService;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.vo.AuthLoginVO;
import com.fragment.labbooking.vo.CurrentUserVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public AuthLoginVO login(AuthLoginDTO dto) {
        String username = dto.getUsername().trim();
        String password = dto.getPassword().trim();

        SysUser sysUser = sysUserService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("limit 1"));
        if (sysUser == null || !passwordMatches(password, sysUser.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        if (!UserStatusConstants.ACTIVE.equals(sysUser.getStatus())) {
            throw new BusinessException(403, "账号已被禁用");
        }

        upgradeLegacyPasswordIfNecessary(sysUser, password);

        LoginUser loginUser = toLoginUser(sysUser);
        AuthLoginVO authLoginVO = new AuthLoginVO();
        authLoginVO.setToken(jwtTokenUtil.generateToken(loginUser));
        authLoginVO.setTokenType(JwtTokenUtil.TOKEN_TYPE);
        authLoginVO.setExpiresIn(jwtTokenUtil.getExpireSeconds());
        authLoginVO.setUser(toCurrentUserVO(loginUser));
        return authLoginVO;
    }

    @Override
    public void register(AuthRegisterDTO dto) {
        String username = dto.getUsername().trim();
        String password = dto.getPassword().trim();
        String confirmPassword = dto.getConfirmPassword().trim();
        String nickname = dto.getNickname().trim();
        String phone = normalizeOptionalText(dto.getPhone());

        if (!password.equals(confirmPassword)) {
            throw new BusinessException("两次输入的密码不一致");
        }

        validatePasswordLength(password);

        boolean exists = sysUserService.count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)) > 0;
        if (exists) {
            throw new BusinessException("用户名已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        SysUser sysUser = new SysUser();
        sysUser.setUsername(username);
        sysUser.setPasswordHash(passwordEncoder.encode(password));
        sysUser.setNickname(nickname);
        sysUser.setPhone(phone);
        sysUser.setRole(UserRoleConstants.USER);
        sysUser.setStatus(UserStatusConstants.ACTIVE);
        sysUser.setCreatedAt(now);
        sysUser.setUpdatedAt(now);

        boolean saved = sysUserService.save(sysUser);
        if (!saved) {
            throw new BusinessException("注册失败");
        }
    }

    @Override
    public void logout(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(401, "未登录或登录已失效");
        }
    }

    @Override
    public void changePassword(LoginUser loginUser, ChangePasswordDTO dto) {
        String oldPassword = dto.getOldPassword().trim();
        String newPassword = dto.getNewPassword().trim();
        String confirmPassword = dto.getConfirmPassword().trim();

        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException("两次输入的新密码不一致");
        }

        validatePasswordLength(newPassword);

        if (oldPassword.equals(newPassword)) {
            throw new BusinessException("新密码不能与原密码相同");
        }

        SysUser sysUser = sysUserService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, loginUser.getId())
                .last("limit 1"));
        if (sysUser == null || !UserStatusConstants.ACTIVE.equals(sysUser.getStatus())) {
            throw new BusinessException(401, "未登录或登录已失效");
        }

        if (!passwordMatches(oldPassword, sysUser.getPasswordHash())) {
            throw new BusinessException("原密码错误");
        }

        sysUser.setPasswordHash(passwordEncoder.encode(newPassword));
        sysUser.setUpdatedAt(LocalDateTime.now());
        boolean updated = sysUserService.updateById(sysUser);
        if (!updated) {
            throw new BusinessException("修改密码失败");
        }
    }

    @Override
    public CurrentUserVO getCurrentUser(LoginUser loginUser) {
        return toCurrentUserVO(loginUser);
    }

    private LoginUser toLoginUser(SysUser sysUser) {
        return new LoginUser(
                sysUser.getId(),
                sysUser.getUsername(),
                sysUser.getNickname(),
                sysUser.getRole(),
                sysUser.getPhone()
        );
    }

    private CurrentUserVO toCurrentUserVO(LoginUser loginUser) {
        CurrentUserVO currentUserVO = new CurrentUserVO();
        BeanUtils.copyProperties(loginUser, currentUserVO);
        return currentUserVO;
    }

    private String normalizeOptionalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private void validatePasswordLength(String password) {
        if (password.length() < 6) {
            throw new BusinessException("密码长度不能少于 6 位");
        }
    }

    private boolean passwordMatches(String rawPassword, String storedPassword) {
        if (!StringUtils.hasText(storedPassword)) {
            return false;
        }
        if (looksLikeBcryptHash(storedPassword)) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        return storedPassword.equals(rawPassword);
    }

    private void upgradeLegacyPasswordIfNecessary(SysUser sysUser, String rawPassword) {
        String storedPassword = sysUser.getPasswordHash();
        if (looksLikeBcryptHash(storedPassword)) {
            return;
        }

        sysUser.setPasswordHash(passwordEncoder.encode(rawPassword));
        sysUser.setUpdatedAt(LocalDateTime.now());
        sysUserService.updateById(sysUser);
    }

    private boolean looksLikeBcryptHash(String value) {
        return StringUtils.hasText(value)
                && (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$"));
    }
}
