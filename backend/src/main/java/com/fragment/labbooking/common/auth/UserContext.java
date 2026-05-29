package com.fragment.labbooking.common.auth;

import com.fragment.labbooking.common.exception.BusinessException;

public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser loginUser) {
        HOLDER.set(loginUser);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static LoginUser requireUser() {
        LoginUser loginUser = HOLDER.get();
        if (loginUser == null) {
            throw new BusinessException(401, "未登录或登录已失效");
        }
        return loginUser;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
