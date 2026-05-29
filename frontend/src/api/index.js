import axios from 'axios'
import { useUserStore } from '@/store/user'

const api = axios.create({
  baseURL: '/api'
})

api.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers.Authorization = `Bearer ${userStore.token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      return Promise.reject(new Error(res.message || '接口异常'))
    }
    return res.data
  },
  (error) => {
    const status = error.response?.status
    const code = error.response?.data?.code ?? status
    const message = error.response?.data?.message || error.message || '请求失败'

    if (status === 401 || code === 401) {
      const userStore = useUserStore()
      userStore.clearUser()
      if (window.location.hash !== '#/login') {
        window.location.hash = '#/login'
      }
    }

    return Promise.reject(new Error(message))
  }
)

export default api
