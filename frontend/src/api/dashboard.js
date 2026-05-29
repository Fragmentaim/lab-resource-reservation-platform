import api from '@/api'

// 获取管理员仪表盘概览
export const fetchAdminDashboard = () => api.get('/admin/dashboard/overview')
