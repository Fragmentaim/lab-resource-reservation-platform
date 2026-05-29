<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NLayout,
  NLayoutSider,
  NLayoutContent,
  NMenu,
  NButton,
  NIcon,
  NText,
  NDropdown,
  NAvatar,
  NModal,
  NInput,
  NForm,
  NFormItem,
  useMessage
} from 'naive-ui'
import {
  BookOutline,
  BusinessOutline,
  CalendarOutline,
  PeopleOutline,
  LogOutOutline,
  PersonOutline,
  LockClosedOutline,
  SettingsOutline,
  StatsChartOutline,
  NotificationsOutline
} from '@vicons/ionicons5'
import { useUserStore } from '@/store/user'
import { changePassword } from '@/api/auth'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const message = useMessage()

const isLoginPage = computed(() => route.path === '/login')

const userMenus = [
  { key: '/resources', label: '资源浏览', icon: BusinessOutline },
  { key: '/my-reservations', label: '我的预约', icon: BookOutline },
  { key: '/notifications', label: '我的通知', icon: NotificationsOutline }
]

const adminMenus = [
  { key: '/admin/overview', label: '系统概览', icon: StatsChartOutline },
  { key: '/admin/users', label: '用户管理', icon: PeopleOutline },
  { key: '/admin/resources', label: '资源与时段', icon: BusinessOutline },
  { key: '/admin/reservations', label: '预约管理', icon: BookOutline },
  { key: '/admin/dict', label: '字典管理', icon: SettingsOutline }
]

const menuOptions = computed(() => {
  return userStore.isAdmin ? adminMenus : userMenus
})

const activeKey = computed(() => route.path)

// 用户下拉菜单
const userDropdownOptions = [
  { label: '个人主页', key: 'profile', icon: () => h(NIcon, null, { default: () => h(PersonOutline) }) },
  { label: '修改密码', key: 'password', icon: () => h(NIcon, null, { default: () => h(LockClosedOutline) }) },
  { type: 'divider', key: 'd1' },
  { label: '退出登录', key: 'logout', icon: () => h(NIcon, null, { default: () => h(LogOutOutline) }) }
]

// 修改密码弹窗
const showPasswordModal = ref(false)
const passwordForm = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})
const submitting = ref(false)
const formRef = ref(null)

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少6位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (rule, value) => value === passwordForm.value.newPassword,
      message: '两次输入的密码不一致',
      trigger: 'blur'
    }
  ]
}

function handleMenuSelect(key) {
  router.push(key)
}

function handleUserDropdown(key) {
  if (key === 'profile') {
    router.push('/profile')
  } else if (key === 'password') {
    passwordForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
    showPasswordModal.value = true
  } else if (key === 'logout') {
    userStore.clearUser()
    router.push('/login')
  }
}

async function handleSubmitPassword() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  try {
    submitting.value = true
    await changePassword(passwordForm.value)
    message.success('密码修改成功，请重新登录')
    showPasswordModal.value = false
    setTimeout(() => {
      userStore.clearUser()
      router.push('/login')
    }, 1500)
  } catch (error) {
    message.error(error.message || '修改密码失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <router-view v-if="isLoginPage" />

  <n-layout v-else has-sider style="height: 100vh">
    <n-layout-sider
      bordered
      collapse-mode="width"
      :collapsed-width="64"
      :width="220"
      :native-scrollbar="false"
      show-trigger
    >
      <div class="logo">
        <n-text strong style="font-size: 20px">实验室预约</n-text>
      </div>

      <n-menu
        :options="menuOptions.map(item => ({
          key: item.key,
          label: item.label,
          icon: () => h(NIcon, null, { default: () => h(item.icon) })
        }))"
        :value="activeKey"
        @update:value="handleMenuSelect"
      />

      <!-- 用户信息 -->
      <div class="user-info">
        <n-dropdown
          :options="userDropdownOptions"
          placement="right-end"
          @select="handleUserDropdown"
        >
          <div class="user-card">
            <n-avatar
              round
              :size="36"
              style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%)"
            >
              <n-icon :component="PersonOutline" />
            </n-avatar>
            <div class="user-text">
              <div class="user-nickname">{{ userStore.userInfo?.nickname || '用户' }}</div>
              <div class="user-role">{{ userStore.isAdmin ? '管理员' : '普通用户' }}</div>
            </div>
          </div>
        </n-dropdown>
      </div>
    </n-layout-sider>

    <n-layout-content :native-scrollbar="false" content-style="padding: 24px;">
      <router-view v-slot="{ Component, route }">
        <transition name="page" mode="out-in">
          <component :is="Component" :key="route.path" />
        </transition>
      </router-view>
    </n-layout-content>
  </n-layout>

  <!-- 修改密码弹窗 -->
  <n-modal
    v-model:show="showPasswordModal"
    preset="card"
    title="修改密码"
    style="width: 420px"
    :bordered="false"
    segmented
  >
    <n-form
      ref="formRef"
      :model="passwordForm"
      :rules="rules"
      label-placement="left"
      label-width="80"
    >
      <n-form-item label="原密码" path="oldPassword">
        <n-input
          v-model:value="passwordForm.oldPassword"
          type="password"
          placeholder="请输入原密码"
          show-password-on="click"
        />
      </n-form-item>
      <n-form-item label="新密码" path="newPassword">
        <n-input
          v-model:value="passwordForm.newPassword"
          type="password"
          placeholder="请输入新密码（至少6位）"
          show-password-on="click"
        />
      </n-form-item>
      <n-form-item label="确认密码" path="confirmPassword">
        <n-input
          v-model:value="passwordForm.confirmPassword"
          type="password"
          placeholder="请再次输入新密码"
          show-password-on="click"
        />
      </n-form-item>
    </n-form>

    <template #footer>
      <n-button @click="showPasswordModal = false">取消</n-button>
      <n-button type="primary" :loading="submitting" @click="handleSubmitPassword" style="margin-left: 12px">
        确认修改
      </n-button>
    </template>
  </n-modal>
</template>

<script>
import { h } from 'vue'
export default {
  methods: { h }
}
</script>

<style scoped>
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid #e0e0e6;
}

.user-info {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 12px;
  border-top: 1px solid #e0e0e6;
  background: #fff;
}

.user-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}

.user-card:hover {
  background: #f5f5f5;
}

.user-text {
  flex: 1;
  min-width: 0;
  text-align: left;
}

.user-nickname {
  font-size: 14px;
  font-weight: 500;
  color: #1a1a1a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-role {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
</style>
