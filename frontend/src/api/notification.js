import api from '@/api'

// 获取通知分页列表
export const fetchNotificationPage = (params = {}) => api.get('/notification/page', { params })

// 获取未读通知数
export const fetchUnreadCount = () => api.get('/notification/unread-count')

// 标记单条已读
export const markAsRead = (id) => api.put(`/notification/${id}/read`)

// 全部标记已读
export const markAllAsRead = () => api.put('/notification/read-all')
