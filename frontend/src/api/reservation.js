import api from '@/api'

// 获取资源的时段列表
export const fetchResourceSlots = (resourceId) =>
  api.get('/resource-slot/list', { params: { resourceId } })

// 获取时段详情
export const fetchSlotById = (id) => api.get(`/resource-slot/${id}`)

// 创建预约
export const createReservation = (data) => api.post('/reservation', data)

// 取消预约
export const cancelReservation = (id, data) => api.put(`/reservation/${id}/cancel`, data)

// 获取我的预约列表
export const fetchMyReservations = () => api.get('/reservation')

// 获取预约详情
export const fetchReservationById = (id) => api.get(`/reservation/${id}`)

// 获取预约分页列表（管理员用）
export const fetchReservationPage = (params = {}) => api.get('/reservation/page', { params })
