<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NIcon,
  NTag,
  NButton,
  NSpace,
  useMessage
} from 'naive-ui'
import {
  PersonOutline,
  AtCircleOutline,
  ShieldCheckmarkOutline,
  CallOutline,
  BookOutline,
  TimeOutline,
  TrendingUpOutline,
  FlameOutline,
  CalendarOutline,
  BusinessOutline
} from '@vicons/ionicons5'
import { fetchCurrentUser } from '@/api/auth'
import { fetchUserOverview } from '@/api/user'
import { useDictStore } from '@/store/dict'

const router = useRouter()
const message = useMessage()
const dictStore = useDictStore()

const userInfo = ref(null)
const userStats = ref(null)
const loading = ref(false)

// 字典数据
const resourceTypeDict = ref([])
const slotTypeDict = ref([])

const getRoleText = (role) => role === 'ADMIN' ? '管理员' : '普通用户'
const getRoleColor = (role) => role === 'ADMIN' ? 'error' : 'info'

// 映射资源类型
const getResourceTypeName = (type) => {
  if (!type) return '暂无'
  const item = resourceTypeDict.value.find(d => d.dictValue === type)
  return item?.dictLabel || type
}

// 映射时段类型
const getSlotTypeName = (type) => {
  if (!type) return '暂无'
  const item = slotTypeDict.value.find(d => d.dictValue === type)
  return item?.dictLabel || type
}

async function loadUserInfo() {
  try {
    loading.value = true
    userInfo.value = await fetchCurrentUser()

    // 加载用户统计数据
    if (userInfo.value?.id) {
      await loadUserStats()
    }
  } catch (error) {
    message.error(error.message || '加载用户信息失败')
  } finally {
    loading.value = false
  }
}

async function loadUserStats() {
  try {
    userStats.value = await fetchUserOverview(userInfo.value.id)
  } catch (error) {
    console.error('加载统计数据失败', error)
  }
}

async function loadDictData() {
  try {
    resourceTypeDict.value = await dictStore.getDictData('resource_type')
    slotTypeDict.value = await dictStore.getDictData('slot_type')
  } catch (error) {
    console.error('加载字典数据失败', error)
  }
}

onMounted(async () => {
  await loadDictData()
  await loadUserInfo()
})
</script>

<template>
  <div class="profile-page">
    <div class="page-header">
      <h1 class="page-title">个人主页</h1>
      <p class="page-subtitle">查看您的个人信息和预约统计</p>
    </div>

    <div class="profile-content">
      <!-- 头像区域 -->
      <div class="avatar-card">
        <div class="avatar">
          <n-icon :component="PersonOutline" :size="48" />
        </div>
        <div class="user-basic">
          <h2 class="nickname">{{ userInfo?.nickname || '用户' }}</h2>
          <n-tag :type="getRoleColor(userInfo?.role)" round>
            {{ getRoleText(userInfo?.role) }}
          </n-tag>
        </div>
      </div>

      <!-- 统计卡片 -->
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-icon" style="background: #667eea15;">
            <n-icon :component="BookOutline" :size="20" style="color: #667eea;" />
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ userStats?.totalReservationCount || 0 }}</div>
            <div class="stat-label">总预约次数</div>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon" style="background: #18a05815;">
            <n-icon :component="TimeOutline" :size="20" style="color: #18a058;" />
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ userStats?.activeReservationCount || 0 }}</div>
            <div class="stat-label">进行中</div>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon" style="background: #2080f015;">
            <n-icon :component="TrendingUpOutline" :size="20" style="color: #2080f0;" />
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ userStats?.recent30DayReservationCount || 0 }}</div>
            <div class="stat-label">近30天</div>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon" style="background: #f0a02015;">
            <n-icon :component="FlameOutline" :size="20" style="color: #f0a020;" />
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ userStats?.finishedReservationCount || 0 }}</div>
            <div class="stat-label">已完成</div>
          </div>
        </div>
      </div>

      <!-- 偏好信息 -->
      <div class="preference-section">
        <h4 class="section-title">我的偏好</h4>
        <div class="preference-grid">
          <div class="preference-item">
            <span class="preference-label">常用资源</span>
            <span class="preference-value">{{ userStats?.favoriteResourceName || '暂无' }}</span>
          </div>
          <div class="preference-item">
            <span class="preference-label">偏好类型</span>
            <span class="preference-value">{{ getResourceTypeName(userStats?.favoriteResourceType) }}</span>
          </div>
          <div class="preference-item">
            <span class="preference-label">偏好时段</span>
            <span class="preference-value">{{ getSlotTypeName(userStats?.favoriteSourceType) }}</span>
          </div>
          <div class="preference-item">
            <span class="preference-label">常用时间</span>
            <span class="preference-value">{{ userStats?.favoriteTimeBucket || '暂无' }}</span>
          </div>
        </div>
      </div>

      <!-- 信息列表 -->
      <div class="info-list">
        <div class="info-row">
          <div class="info-label">
            <n-icon :component="AtCircleOutline" :size="18" />
            <span>用户名</span>
          </div>
          <div class="info-value">@{{ userInfo?.username || '-' }}</div>
        </div>

        <div class="info-row">
          <div class="info-label">
            <n-icon :component="PersonOutline" :size="18" />
            <span>昵称</span>
          </div>
          <div class="info-value">{{ userInfo?.nickname || '-' }}</div>
        </div>

        <div class="info-row">
          <div class="info-label">
            <n-icon :component="CallOutline" :size="18" />
            <span>手机号</span>
          </div>
          <div class="info-value">{{ userInfo?.phone || '未设置' }}</div>
        </div>

        <div class="info-row">
          <div class="info-label">
            <n-icon :component="ShieldCheckmarkOutline" :size="18" />
            <span>用户角色</span>
          </div>
          <div class="info-value">
            <n-tag :type="getRoleColor(userInfo?.role)" size="small">
              {{ getRoleText(userInfo?.role) }}
            </n-tag>
          </div>
        </div>
      </div>

      <!-- 快捷操作 -->
      <div class="quick-actions">
        <n-button type="primary" size="large" @click="router.push('/my-reservations')">
          <template #icon><n-icon :component="CalendarOutline" /></template>
          查看我的预约
        </n-button>
        <n-button size="large" @click="router.push('/resources')">
          <template #icon><n-icon :component="BusinessOutline" /></template>
          浏览资源
        </n-button>
      </div>

      <!-- 提示 -->
      <div class="tips">
        <span class="tips-icon">💡</span>
        <span>如需修改个人信息，请点击左下角头像选择"修改密码"，或联系管理员</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile-page {
  min-height: 100%;
  background: #fff;
  padding: 24px;
}

.page-header {
  margin-bottom: 24px;
}

.page-title {
  font-size: 36px;
  font-weight: 700;
  color: #1a1a1a;
  margin: 0 0 12px 0;
  letter-spacing: -1px;
}

.page-subtitle {
  font-size: 15px;
  color: #666;
  margin: 0;
}

.profile-content {
  max-width: 900px;
}

/* 头像卡片 */
.avatar-card {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 32px;
  background: #fff;
  border-radius: 16px;
  margin-bottom: 24px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  border: 1px solid #f0f0f0;
}

.avatar {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 8px 24px rgba(102, 126, 234, 0.3);
  position: relative;
}

.avatar::after {
  content: '';
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  border: 3px solid rgba(102, 126, 234, 0.2);
}

.user-basic {
  flex: 1;
}

.nickname {
  font-size: 28px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 12px 0;
}

/* 统计卡片网格 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #f0f0f0;
  transition: all 0.2s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.stat-card:hover {
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  transform: translateY(-2px);
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #1a1a1a;
  line-height: 1;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 13px;
  color: #999;
}

/* 偏好信息 */
.preference-section {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  margin-bottom: 24px;
  border: 1px solid #f0f0f0;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 16px 0;
}

.preference-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}

.preference-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.preference-label {
  font-size: 12px;
  color: #999;
}

.preference-value {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
}

/* 信息列表 */
.info-list {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 12px;
  overflow: hidden;
  margin-bottom: 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #666;
}

.info-value {
  font-size: 15px;
  color: #1a1a1a;
  font-weight: 500;
}

/* 快捷操作 */
.quick-actions {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}

/* 提示 */
.tips {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #fff7e6;
  border-radius: 8px;
  font-size: 13px;
  color: #8b6914;
}

.tips-icon {
  font-size: 16px;
}

@media (max-width: 768px) {
  .profile-page {
    padding: 16px;
  }

  .page-title {
    font-size: 28px;
  }

  .avatar-card {
    flex-direction: column;
    text-align: center;
  }

  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .quick-actions {
    flex-direction: column;
  }
}
</style>
