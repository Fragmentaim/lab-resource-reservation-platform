<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  NCard,
  NTabs,
  NTabPane,
  NForm,
  NFormItem,
  NInput,
  NButton,
  NSpace,
  NIcon,
  NText,
  useMessage
} from 'naive-ui'
import {
  PersonOutline,
  LockClosedOutline,
  AtCircleOutline,
  CallOutline,
  EyeOutline,
  EyeOffOutline
} from '@vicons/ionicons5'
import { useUserStore } from '@/store/user'
import { login, register } from '@/api/auth'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const userStore = useUserStore()

// 当前选中的标签页
const activeTab = ref('login')

// 表单引用
const loginFormRef = ref()
const registerFormRef = ref()

// 加载状态
const loginLoading = ref(false)
const registerLoading = ref(false)

// 登录表单
const loginForm = ref({
  username: '',
  password: ''
})

// 注册表单
const registerForm = ref({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
  phone: ''
})

// 登录表单验证规则
const loginRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 20, message: '用户名长度为 2-20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ]
}

// 注册表单验证规则
const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 20, message: '用户名长度为 2-20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value) => {
        return value === registerForm.value.password
      },
      message: '两次输入的密码不一致',
      trigger: 'blur'
    }
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 20, message: '昵称长度为 2-20 个字符', trigger: 'blur' }
  ],
  phone: [
    {
      pattern: /^1[3-9]\d{9}$/,
      message: '请输入正确的手机号码',
      trigger: 'blur'
    }
  ]
}

// 处理登录
async function handleLogin() {
  try {
    await loginFormRef.value.validate()
    loginLoading.value = true

    const data = await login({
      username: loginForm.value.username.trim(),
      password: loginForm.value.password.trim()
    })

    userStore.setUser(data)
    message.success('登录成功，欢迎回来！')

    // 跳转到之前的页面或首页
    const redirect = route.query.redirect || '/'
    router.push(redirect)
  } catch (error) {
    message.error(error.message || '登录失败，请重试')
  } finally {
    loginLoading.value = false
  }
}

// 处理注册
async function handleRegister() {
  try {
    await registerFormRef.value.validate()
    registerLoading.value = true

    await register({
      username: registerForm.value.username.trim(),
      password: registerForm.value.password.trim(),
      confirmPassword: registerForm.value.confirmPassword.trim(),
      nickname: registerForm.value.nickname.trim(),
      phone: registerForm.value.phone?.trim() || null
    })

    message.success('注册成功，请登录')
    activeTab.value = 'login'

    // 自动填充用户名
    loginForm.value.username = registerForm.value.username

    // 清空注册表单
    registerForm.value = {
      username: '',
      password: '',
      confirmPassword: '',
      nickname: '',
      phone: ''
    }
  } catch (error) {
    message.error(error.message || '注册失败，请重试')
  } finally {
    registerLoading.value = false
  }
}

// 切换标签时清空表单验证
watch(activeTab, () => {
  loginFormRef.value?.restoreValidation()
  registerFormRef.value?.restoreValidation()
})
</script>

<template>
  <div class="login-wrapper">
    <!-- 背景装饰 -->
    <div class="bg-decoration">
      <div class="circle circle-1"></div>
      <div class="circle circle-2"></div>
      <div class="circle circle-3"></div>
      <div class="circle circle-4"></div>
    </div>

    <!-- 登录卡片 -->
    <div class="login-card-wrapper">
      <!-- Logo 和标题 -->
      <div class="header">
        <div class="logo-container">
          <div class="logo">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M19 3H5C3.89 3 3 3.89 3 5V19C3 20.11 3.89 21 5 21H19C20.11 21 21 20.11 21 19V5C21 3.89 20.11 3 19 3ZM19 19H5V5H19V19ZM7 10H9V17H7V10ZM11 7H13V17H11V7ZM15 13H17V17H15V13Z" fill="currentColor"/>
            </svg>
          </div>
        </div>
        <h1 class="title">实验室预约系统</h1>
        <p class="subtitle">Laboratory Resource Booking System</p>
      </div>

      <!-- 登录/注册表单 -->
      <n-card class="form-card" :bordered="false">
        <n-tabs v-model:value="activeTab" size="large" animated>
          <!-- 登录标签页 -->
          <n-tab-pane name="login" tab="登录">
            <n-form
              ref="loginFormRef"
              :model="loginForm"
              :rules="loginRules"
              label-placement="left"
              :label-width="0"
            >
              <n-form-item path="username">
                <n-input
                  v-model:value="loginForm.username"
                  placeholder="请输入用户名"
                  size="large"
                  clearable
                  @keyup.enter="handleLogin"
                >
                  <template #prefix>
                    <n-icon :component="PersonOutline" />
                  </template>
                </n-input>
              </n-form-item>

              <n-form-item path="password">
                <n-input
                  v-model:value="loginForm.password"
                  type="password"
                  placeholder="请输入密码"
                  size="large"
                  show-password-on="click"
                  @keyup.enter="handleLogin"
                >
                  <template #prefix>
                    <n-icon :component="LockClosedOutline" />
                  </template>
                </n-input>
              </n-form-item>

              <div class="form-actions">
                <n-button
                  type="primary"
                  size="large"
                  block
                  :loading="loginLoading"
                  @click="handleLogin"
                >
                  登录
                </n-button>
              </div>
            </n-form>
          </n-tab-pane>

          <!-- 注册标签页 -->
          <n-tab-pane name="register" tab="注册">
            <n-form
              ref="registerFormRef"
              :model="registerForm"
              :rules="registerRules"
              label-placement="left"
              :label-width="0"
            >
              <n-form-item path="username">
                <n-input
                  v-model:value="registerForm.username"
                  placeholder="请输入用户名"
                  size="large"
                  clearable
                >
                  <template #prefix>
                    <n-icon :component="PersonOutline" />
                  </template>
                </n-input>
              </n-form-item>

              <n-form-item path="nickname">
                <n-input
                  v-model:value="registerForm.nickname"
                  placeholder="请输入昵称"
                  size="large"
                  clearable
                >
                  <template #prefix>
                    <n-icon :component="AtCircleOutline" />
                  </template>
                </n-input>
              </n-form-item>

              <n-form-item path="password">
                <n-input
                  v-model:value="registerForm.password"
                  type="password"
                  placeholder="请输入密码（至少6位）"
                  size="large"
                  show-password-on="click"
                >
                  <template #prefix>
                    <n-icon :component="LockClosedOutline" />
                  </template>
                </n-input>
              </n-form-item>

              <n-form-item path="confirmPassword">
                <n-input
                  v-model:value="registerForm.confirmPassword"
                  type="password"
                  placeholder="请再次输入密码"
                  size="large"
                  show-password-on="click"
                >
                  <template #prefix>
                    <n-icon :component="LockClosedOutline" />
                  </template>
                </n-input>
              </n-form-item>

              <n-form-item path="phone">
                <n-input
                  v-model:value="registerForm.phone"
                  placeholder="请输入手机号（选填）"
                  size="large"
                  clearable
                >
                  <template #prefix>
                    <n-icon :component="CallOutline" />
                  </template>
                </n-input>
              </n-form-item>

              <div class="form-actions">
                <n-button
                  type="primary"
                  size="large"
                  block
                  :loading="registerLoading"
                  @click="handleRegister"
                >
                  注册
                </n-button>
              </div>
            </n-form>
          </n-tab-pane>
        </n-tabs>
      </n-card>

      <!-- 底部信息 -->
      <div class="footer">
        <n-text depth="3">© 2026 实验室预约系统 · All Rights Reserved</n-text>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-wrapper {
  position: relative;
  width: 100%;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  overflow: hidden;
}

/* 背景装饰圆圈 */
.bg-decoration {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.circle {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  animation: float 20s infinite ease-in-out;
}

.circle-1 {
  width: 400px;
  height: 400px;
  top: -100px;
  left: -100px;
  animation-delay: 0s;
}

.circle-2 {
  width: 300px;
  height: 300px;
  bottom: -50px;
  right: -50px;
  animation-delay: 5s;
}

.circle-3 {
  width: 200px;
  height: 200px;
  top: 50%;
  right: 10%;
  animation-delay: 10s;
}

.circle-4 {
  width: 150px;
  height: 150px;
  bottom: 20%;
  left: 15%;
  animation-delay: 15s;
}

@keyframes float {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  33% {
    transform: translate(30px, -30px) scale(1.1);
  }
  66% {
    transform: translate(-20px, 20px) scale(0.9);
  }
}

/* 登录卡片容器 */
.login-card-wrapper {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 440px;
  padding: 0 20px;
}

/* 头部 */
.header {
  text-align: center;
  margin-bottom: 32px;
}

.logo-container {
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
}

.logo {
  width: 64px;
  height: 64px;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  backdrop-filter: blur(10px);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.logo:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.15);
}

.logo svg {
  width: 36px;
  height: 36px;
  color: #667eea;
}

.title {
  font-size: 28px;
  font-weight: 600;
  color: #fff;
  margin: 0 0 8px 0;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  letter-spacing: -0.5px;
}

.subtitle {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.85);
  margin: 0;
  font-weight: 400;
  letter-spacing: 0.5px;
}

/* 表单卡片 */
.form-card {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  backdrop-filter: blur(10px);
  overflow: hidden;
  transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.form-card:hover {
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.2);
}

.form-card :deep(.n-card__content) {
  padding: 32px 40px;
}

.form-card :deep(.n-tabs-nav) {
  justify-content: center;
}

.form-card :deep(.n-tabs-tab) {
  font-size: 16px;
  font-weight: 500;
  padding: 12px 24px;
}

.form-card :deep(.n-form-item) {
  margin-bottom: 20px;
}

.form-card :deep(.n-form-item:last-child) {
  margin-bottom: 0;
}

.form-card :deep(.n-input) {
  font-size: 15px;
}

.form-card :deep(.n-input .n-input__input-el) {
  height: 44px;
}

.form-card :deep(.n-input .n-input__prefix) {
  margin-right: 12px;
}

/* 表单操作区 */
.form-actions {
  margin-top: 8px;
}

.form-actions :deep(.n-button) {
  height: 48px;
  font-size: 16px;
  font-weight: 500;
  border-radius: 8px;
  transition: all 0.3s ease;
}

.form-actions :deep(.n-button:hover) {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(102, 126, 234, 0.4);
}

/* 底部 */
.footer {
  text-align: center;
  margin-top: 32px;
}

.footer :deep(.n-text) {
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
}

/* 响应式 */
@media (max-width: 480px) {
  .login-card-wrapper {
    padding: 0 16px;
  }

  .title {
    font-size: 24px;
  }

  .form-card :deep(.n-card__content) {
    padding: 24px 20px;
  }

  .circle-1 {
    width: 250px;
    height: 250px;
  }

  .circle-2 {
    width: 200px;
    height: 200px;
  }
}
</style>
