import api from '@/api'

// 获取用户分页列表
export const fetchUserPage = (params = {}) => api.get('/user/page', { params })

// 获取用户详情
export const fetchUserById = (id) => api.get(`/user/${id}`)

// 创建用户
export const createUser = (data) => api.post('/user', data)

// 更新用户
export const updateUser = (data) => api.put('/user', data)

// 更新用户状态
export const updateUserStatus = (id, data) => api.put(`/user/${id}/status`, data)

// 重置密码
export const resetUserPassword = (id) => api.put(`/user/${id}/reset-password`)

// 获取用户预约概览
export const fetchUserOverview = (id) => api.get(`/user/${id}/overview`)

// 获取用户预约记录
export const fetchUserReservations = (id, params = {}) => api.get(`/user/${id}/reservations`, { params })
