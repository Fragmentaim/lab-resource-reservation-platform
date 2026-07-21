package com.fragment.labbooking.common.auth;

import com.fragment.labbooking.common.constants.UserRoleConstants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private Long id;
    private String username;
    private String nickname;
    private String role;
    private String phone;

    public boolean isAdmin() {
        return UserRoleConstants.ADMIN.equals(role);
    }
}
