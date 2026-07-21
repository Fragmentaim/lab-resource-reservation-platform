package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.audit.AdminAuditHelper;
import com.fragment.labbooking.common.constants.UserRoleConstants;
import com.fragment.labbooking.common.constants.UserStatusConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.dto.UserAddDTO;
import com.fragment.labbooking.dto.UserPageQueryDTO;
import com.fragment.labbooking.dto.UserUpdateDTO;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.mapper.SysUserMapper;
import com.fragment.labbooking.service.SysUserService;
import com.fragment.labbooking.vo.UserVO;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser>
        implements SysUserService {

    private static final String DEFAULT_RESET_PASSWORD = "123456";
    private static final Set<String> ALLOWED_ROLES = Set.of(
            UserRoleConstants.USER,
            UserRoleConstants.ADMIN
    );
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            UserStatusConstants.ACTIVE,
            UserStatusConstants.LOCKED
    );

    private final PasswordEncoder passwordEncoder;
    private final AdminAuditHelper adminAuditHelper;

    public SysUserServiceImpl(PasswordEncoder passwordEncoder,
                              AdminAuditHelper adminAuditHelper) {
        this.passwordEncoder = passwordEncoder;
        this.adminAuditHelper = adminAuditHelper;
    }

    @Override
    public Page<UserVO> pageUsers(UserPageQueryDTO queryDTO) {
        UserPageQueryDTO actualQuery = queryDTO == null ? new UserPageQueryDTO() : queryDTO;
        long pageNum = actualQuery.getPageNum() == null ? 1L : actualQuery.getPageNum();
        long pageSize = actualQuery.getPageSize() == null ? 10L : actualQuery.getPageSize();

        Page<SysUser> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(StringUtils.hasText(actualQuery.getRole()), SysUser::getRole, actualQuery.getRole())
                .eq(StringUtils.hasText(actualQuery.getStatus()), SysUser::getStatus, actualQuery.getStatus())
                .and(StringUtils.hasText(actualQuery.getKeyword()), w -> w
                        .like(SysUser::getUsername, actualQuery.getKeyword())
                        .or()
                        .like(SysUser::getNickname, actualQuery.getKeyword())
                        .or()
                        .like(SysUser::getPhone, actualQuery.getKeyword()))
                .orderByDesc(SysUser::getCreatedAt)
                .orderByDesc(SysUser::getId);

        Page<SysUser> userPage = this.page(page, wrapper);
        List<UserVO> records = userPage.getRecords().stream()
                .map(this::toUserVO)
                .collect(Collectors.toList());

        Page<UserVO> voPage = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public UserVO getUserById(Long id) {
        if (id == null) {
            throw new BusinessException("用户ID不能为空");
        }

        SysUser sysUser = this.getById(id);
        if (sysUser == null) {
            throw new BusinessException("用户不存在");
        }

        return toUserVO(sysUser);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addUser(UserAddDTO dto) {
        SysUser sysUser = new SysUser();
        adminAuditHelper.execute(
                "USER",
                "ADD",
                "SYS_USER",
                sysUser::getId,
                () -> "username=" + trimForSummary(dto.getUsername())
                        + ", role=" + trimForSummary(dto.getRole())
                        + ", status=" + trimForSummary(dto.getStatus()),
                () -> {
                    String username = normalizeRequiredText(dto.getUsername(), "用户名不能为空");
                    String password = normalizeRequiredText(dto.getPassword(), "密码不能为空");
                    String nickname = normalizeRequiredText(dto.getNickname(), "昵称不能为空");
                    String role = normalizeRequiredText(dto.getRole(), "角色不能为空");
                    String status = normalizeRequiredText(dto.getStatus(), "状态不能为空");

                    validateUsernameUnique(username, null);
                    validateRole(role);
                    validateStatus(status);
                    validatePassword(password);

                    LocalDateTime now = LocalDateTime.now();
                    sysUser.setUsername(username);
                    sysUser.setPasswordHash(passwordEncoder.encode(password));
                    sysUser.setNickname(nickname);
                    sysUser.setRole(role);
                    sysUser.setPhone(normalizeOptionalText(dto.getPhone()));
                    sysUser.setStatus(status);
                    sysUser.setCreatedAt(now);
                    sysUser.setUpdatedAt(now);

                    if (!this.save(sysUser)) {
                        throw new BusinessException("新增用户失败");
                    }
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(UserUpdateDTO dto, Long operatorId) {
        SysUser existing = requireExistingUser(dto.getId());
        adminAuditHelper.execute(
                "USER",
                "UPDATE",
                "SYS_USER",
                existing::getId,
                () -> "username=" + existing.getUsername()
                        + ", role=" + existing.getRole()
                        + ", status=" + existing.getStatus(),
                () -> {
                    String username = normalizeRequiredText(dto.getUsername(), "用户名不能为空");
                    String nickname = normalizeRequiredText(dto.getNickname(), "昵称不能为空");
                    String role = normalizeRequiredText(dto.getRole(), "角色不能为空");
                    String status = normalizeRequiredText(dto.getStatus(), "状态不能为空");

                    validateUsernameUnique(username, existing.getId());
                    validateRole(role);
                    validateStatus(status);
                    validateSelfProtection(existing.getId(), role, status, operatorId);
                    validateLastActiveAdminProtection(existing, role, status);

                    existing.setUsername(username);
                    existing.setNickname(nickname);
                    existing.setRole(role);
                    existing.setPhone(normalizeOptionalText(dto.getPhone()));
                    existing.setStatus(status);
                    existing.setUpdatedAt(LocalDateTime.now());

                    if (!this.updateById(existing)) {
                        throw new BusinessException("更新用户失败");
                    }
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserStatus(Long id, String status, Long operatorId) {
        SysUser existing = requireExistingUser(id);
        adminAuditHelper.execute(
                "USER",
                "UPDATE_STATUS",
                "SYS_USER",
                existing::getId,
                () -> "username=" + existing.getUsername() + ", status=" + existing.getStatus(),
                () -> {
                    String normalizedStatus = normalizeRequiredText(status, "状态不能为空");
                    validateStatus(normalizedStatus);
                    validateSelfProtection(existing.getId(), existing.getRole(), normalizedStatus, operatorId);
                    validateLastActiveAdminProtection(existing, existing.getRole(), normalizedStatus);

                    existing.setStatus(normalizedStatus);
                    existing.setUpdatedAt(LocalDateTime.now());
                    if (!this.updateById(existing)) {
                        throw new BusinessException("更新用户状态失败");
                    }
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id) {
        SysUser existing = requireExistingUser(id);
        adminAuditHelper.execute(
                "USER",
                "RESET_PASSWORD",
                "SYS_USER",
                existing::getId,
                () -> "username=" + existing.getUsername() + ", defaultReset=true",
                () -> {
                    existing.setPasswordHash(passwordEncoder.encode(DEFAULT_RESET_PASSWORD));
                    existing.setUpdatedAt(LocalDateTime.now());
                    if (!this.updateById(existing)) {
                        throw new BusinessException("重置密码失败");
                    }
                }
        );
    }

    private SysUser requireExistingUser(Long id) {
        if (id == null) {
            throw new BusinessException("用户ID不能为空");
        }

        SysUser sysUser = this.getById(id);
        if (sysUser == null) {
            throw new BusinessException("用户不存在");
        }
        return sysUser;
    }

    private void validateUsernameUnique(String username, Long excludeId) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username);
        if (excludeId != null) {
            wrapper.ne(SysUser::getId, excludeId);
        }

        if (this.count(wrapper) > 0) {
            throw new BusinessException("用户名已存在");
        }
    }

    private void validateRole(String role) {
        if (!ALLOWED_ROLES.contains(role)) {
            throw new BusinessException("用户角色不合法");
        }
    }

    private void validateStatus(String status) {
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new BusinessException("用户状态不合法");
        }
    }

    private void validatePassword(String password) {
        if (password.length() < 6) {
            throw new BusinessException("密码长度不能少于 6 位");
        }
    }

    private void validateSelfProtection(Long userId, String role, String status, Long operatorId) {
        if (operatorId == null || !operatorId.equals(userId)) {
            return;
        }

        if (!UserRoleConstants.ADMIN.equals(role)) {
            throw new BusinessException("不能修改当前管理员账号的角色");
        }

        if (!UserStatusConstants.ACTIVE.equals(status)) {
            throw new BusinessException("不能禁用当前登录账号");
        }
    }

    private void validateLastActiveAdminProtection(SysUser existing, String nextRole, String nextStatus) {
        boolean currentlyActiveAdmin = UserRoleConstants.ADMIN.equals(existing.getRole())
                && UserStatusConstants.ACTIVE.equals(existing.getStatus());
        boolean willRemainActiveAdmin = UserRoleConstants.ADMIN.equals(nextRole)
                && UserStatusConstants.ACTIVE.equals(nextStatus);

        if (!currentlyActiveAdmin || willRemainActiveAdmin) {
            return;
        }

        long activeAdminCount = this.count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserRoleConstants.ADMIN)
                .eq(SysUser::getStatus, UserStatusConstants.ACTIVE));

        if (activeAdminCount <= 1) {
            throw new BusinessException("不能停用或降级系统中的最后一个启用管理员");
        }
    }

    private String normalizeRequiredText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String trimForSummary(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private UserVO toUserVO(SysUser sysUser) {
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(sysUser, userVO);
        return userVO;
    }
}
