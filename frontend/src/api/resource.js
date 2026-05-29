import api from '@/api'

// 获取资源列表
export const fetchResourceList = (params = {}) => api.get('/resource/list', { params })

// 获取资源详情
export const fetchResourceById = (id) => api.get(`/resource/${id}`)

// 获取资源分页列表（管理员用）
export const fetchResourcePage = (params = {}) => api.get('/resource/page', { params })

// 创建资源
export const createResource = (data) => api.post('/resource', data)

// 更新资源
export const updateResource = (data) => api.put('/resource', data)

// 删除资源
export const deleteResource = (id) => api.delete(`/resource/${id}`)
