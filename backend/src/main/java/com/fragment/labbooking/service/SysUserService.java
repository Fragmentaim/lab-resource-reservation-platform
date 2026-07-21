package com.fragment.labbooking.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.dto.UserAddDTO;
import com.fragment.labbooking.dto.UserPageQueryDTO;
import com.fragment.labbooking.dto.UserUpdateDTO;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.vo.UserVO;

public interface SysUserService extends IService<SysUser> {

    Page<UserVO> pageUsers(UserPageQueryDTO queryDTO);

    UserVO getUserById(Long id);

    void addUser(UserAddDTO dto);

    void updateUser(UserUpdateDTO dto, Long operatorId);

    void updateUserStatus(Long id, String status, Long operatorId);

    void resetPassword(Long id);
}
