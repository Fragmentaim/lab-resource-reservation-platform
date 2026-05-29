package com.fragment.labbooking.controller;

import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.result.Result;
import com.fragment.labbooking.dto.AuthLoginDTO;
import com.fragment.labbooking.dto.AuthRegisterDTO;
import com.fragment.labbooking.dto.ChangePasswordDTO;
import com.fragment.labbooking.service.AuthService;
import com.fragment.labbooking.vo.AuthLoginVO;
import com.fragment.labbooking.vo.CurrentUserVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/login")
    public Result<AuthLoginVO> login(@Valid @RequestBody AuthLoginDTO dto) {
        return Result.success(authService.login(dto));
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody AuthRegisterDTO dto) {
        authService.register(dto);
        return Result.success();
    }

    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.success(authService.getCurrentUser(UserContext.requireUser()));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout(UserContext.requireUser());
        return Result.success();
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        authService.changePassword(UserContext.requireUser(), dto);
        return Result.success();
    }
}
