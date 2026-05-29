import api from '@/api'

// 登录
export const login = (data) => api.post('/auth/login', data)

// 注册
export const register = (data) => api.post('/auth/register', data)

// 获取当前用户信息
export const fetchCurrentUser = () => api.get('/auth/me')

// 退出登录
export const logout = () => api.post('/auth/logout')

// 修改密码
export const changePassword = (data) => api.put('/auth/password', data)
