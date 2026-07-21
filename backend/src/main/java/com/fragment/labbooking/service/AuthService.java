package com.fragment.labbooking.service;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.dto.AuthLoginDTO;
import com.fragment.labbooking.dto.AuthRegisterDTO;
import com.fragment.labbooking.dto.ChangePasswordDTO;
import com.fragment.labbooking.vo.AuthLoginVO;
import com.fragment.labbooking.vo.CurrentUserVO;

public interface AuthService {

    AuthLoginVO login(AuthLoginDTO dto);

    void register(AuthRegisterDTO dto);

    void logout(LoginUser loginUser);

    void changePassword(LoginUser loginUser, ChangePasswordDTO dto);

    CurrentUserVO getCurrentUser(LoginUser loginUser);
}
