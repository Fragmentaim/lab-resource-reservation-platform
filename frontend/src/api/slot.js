import api from '@/api'

// 获取时段分页列表
export const fetchSlotPage = (params = {}) => api.get('/resource-slot/page', { params })

// 获取时段详情
export const fetchSlotById = (id) => api.get(`/resource-slot/${id}`)

// 创建时段
export const createSlot = (data) => api.post('/resource-slot', data)

// 更新时段
export const updateSlot = (data) => api.put('/resource-slot', data)

// 删除时段
export const deleteSlot = (id) => api.delete(`/resource-slot/${id}`)
