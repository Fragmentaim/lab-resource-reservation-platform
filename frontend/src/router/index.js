import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/store/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    redirect: '/resources'
  },
  {
    path: '/admin',
    redirect: '/admin/overview'
  },
  {
    path: '/resources',
    name: 'Resources',
    component: () => import('@/views/Resources.vue'),
    meta: { title: '资源浏览' }
  },
  {
    path: '/resources/:id',
    name: 'ResourceDetail',
    component: () => import('@/views/ResourceDetail.vue'),
    meta: { title: '资源详情' }
  },
  {
    path: '/my-reservations',
    name: 'MyReservations',
    component: () => import('@/views/MyReservations.vue'),
    meta: { title: '我的预约' }
  },
  {
    path: '/notifications',
    name: 'Notifications',
    component: () => import('@/views/Notifications.vue'),
    meta: { title: '我的通知' }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('@/views/Profile.vue'),
    meta: { title: '个人主页' }
  },
  {
    path: '/admin/overview',
    name: 'AdminOverview',
    component: () => import('@/views/admin/Overview.vue'),
    meta: { title: '系统概览', admin: true }
  },
  {
    path: '/admin/users',
    name: 'AdminUsers',
    component: () => import('@/views/admin/Users.vue'),
    meta: { title: '用户管理', admin: true }
  },
  {
    path: '/admin/resources',
    name: 'AdminResources',
    component: () => import('@/views/admin/Resources.vue'),
    meta: { title: '资源与时段管理', admin: true }
  },
  {
    path: '/admin/reservations',
    name: 'AdminReservations',
    component: () => import('@/views/admin/Reservations.vue'),
    meta: { title: '预约管理', admin: true }
  },
  {
    path: '/admin/dict',
    name: 'AdminDict',
    component: () => import('@/views/admin/Dict.vue'),
    meta: { title: '字典管理', admin: true }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const userStore = useUserStore()

  if (to.meta.public) {
    next()
    return
  }

  if (!userStore.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (to.meta.admin && !userStore.isAdmin) {
    next('/resources')
    return
  }

  next()
})

export default router
