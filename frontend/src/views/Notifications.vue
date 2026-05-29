<script setup>
import { ref, onMounted, computed } from 'vue'
import {
  NCard,
  NSpace,
  NButton,
  NIcon,
  NTag,
  NSpin,
  NEmpty,
  NPagination,
  useMessage
} from 'naive-ui'
import {
  NotificationsOutline,
  RefreshOutline,
  CheckmarkDoneOutline,
  CheckmarkCircleOutline,
  CalendarOutline,
  BookOutline
} from '@vicons/ionicons5'
import { fetchNotificationPage, fetchUnreadCount, markAsRead, markAllAsRead } from '@/api/notification'

const message = useMessage()
const loading = ref(false)
const notifications = ref([])
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const unreadCount = ref(0)

// 筛选
const filterRead = ref(null)

const filterOptions = [
  { label: '全部', value: null },
  { label: '未读', value: false },
  { label: '已读', value: true }
]

const filteredNotifications = computed(() => {
  if (filterRead.value === null) return notifications.value
  return notifications.value.filter(n => n.read === filterRead.value)
})

async function loadNotifications() {
  try {
    loading.value = true
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value
    }
    const data = await fetchNotificationPage(params)
    notifications.value = data.records
    total.value = data.total
    await loadUnreadCount()
  } catch (error) {
    message.error(error.message || '加载通知失败')
  } finally {
    loading.value = false
  }
}

async function loadUnreadCount() {
  try {
    unreadCount.value = await fetchUnreadCount()
  } catch (error) {
    console.error('获取未读数失败', error)
  }
}

async function handleMarkRead(notification) {
  try {
    await markAsRead(notification.id)
    notification.read = true
    await loadUnreadCount()
  } catch (error) {
    message.error(error.message || '操作失败')
  }
}

async function handleMarkAllRead() {
  try {
    await markAllAsRead()
    message.success('已全部标记为已读')
    await loadNotifications()
  } catch (error) {
    message.error(error.message || '操作失败')
  }
}

function handlePageChange(page) {
  currentPage.value = page
  loadNotifications()
}

function handlePageSizeChange(size) {
  pageSize.value = size
  currentPage.value = 1
  loadNotifications()
}

const formatDateTime = (datetime) => {
  if (!datetime) return '-'
  const date = new Date(datetime)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}`
}

const getTypeIcon = (type) => {
  return { REMINDER: CalendarOutline, RESERVATION: BookOutline }[type] || NotificationsOutline
}

const getTypeText = (type) => {
  return { REMINDER: '预约提醒', RESERVATION: '预约通知' }[type] || '系统通知'
}

const getTypeColor = (type) => {
  return { REMINDER: 'warning', RESERVATION: 'info' }[type] || 'default'
}

onMounted(() => {
  loadNotifications()
})
</script>

<template>
  <div class="notifications-page">
    <div class="page-header">
      <h1 class="page-title">我的通知</h1>
      <p class="page-subtitle">查看您的预约提醒和系统通知</p>
    </div>

    <!-- 筛选栏 -->
    <n-card class="filter-card" :bordered="false">
      <n-space :size="12" align="center" justify="space-between" wrap>
        <n-space :size="12">
          <n-button
            v-for="opt in filterOptions"
            :key="String(opt.value)"
            :type="filterRead === opt.value ? 'primary' : 'default'"
            size="small"
            @click="filterRead = opt.value"
          >
            {{ opt.label }}
            <template v-if="opt.value === false && unreadCount > 0">
              ({{ unreadCount }})
            </template>
          </n-button>
        </n-space>
        <n-space :size="8">
          <n-button size="small" :disabled="unreadCount === 0" @click="handleMarkAllRead">
            <template #icon><n-icon :component="CheckmarkDoneOutline" /></template>
            全部已读
          </n-button>
          <n-button size="small" @click="loadNotifications">
            <template #icon><n-icon :component="RefreshOutline" /></template>
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <!-- 通知列表 -->
    <div class="notifications-container">
      <n-spin :show="loading">
        <n-empty v-if="!loading && filteredNotifications.length === 0" description="暂无通知" style="margin-top: 80px" />

        <div v-else class="notification-list">
          <n-card
            v-for="notification in filteredNotifications"
            :key="notification.id"
            class="notification-card"
            :class="{ unread: !notification.read }"
            :bordered="false"
          >
            <div class="card-content">
              <div class="card-left">
                <div class="notification-icon" :class="getTypeColor(notification.type)">
                  <n-icon :component="getTypeIcon(notification.type)" :size="20" />
                </div>
              </div>

              <div class="card-middle">
                <div class="notification-header">
                  <n-tag :type="getTypeColor(notification.type)" size="small" round>
                    {{ getTypeText(notification.type) }}
                  </n-tag>
                  <span v-if="!notification.read" class="unread-dot"></span>
                </div>
                <div class="notification-title">{{ notification.title }}</div>
                <div class="notification-content">{{ notification.content }}</div>
                <div class="notification-time">
                  <n-icon :component="CalendarOutline" :size="14" />
                  {{ formatDateTime(notification.createdAt) }}
                </div>
              </div>

              <div class="card-right">
                <n-button
                  v-if="!notification.read"
                  text
                  type="primary"
                  size="small"
                  @click="handleMarkRead(notification)"
                >
                  <template #icon><n-icon :component="CheckmarkCircleOutline" /></template>
                  标记已读
                </n-button>
              </div>
            </div>
          </n-card>
        </div>

        <div v-if="total > 0" class="pagination-wrapper">
          <n-pagination
            v-model:page="currentPage"
            v-model:page-size="pageSize"
            :page-count="Math.ceil(total / pageSize)"
            :page-sizes="[10, 20, 50]"
            show-size-picker
            show-quick-jumper
            @update:page="handlePageChange"
            @update:page-size="handlePageSizeChange"
          >
            <template #prefix="{ itemCount }">共 {{ itemCount }} 条</template>
          </n-pagination>
        </div>
      </n-spin>
    </div>
  </div>
</template>

<style scoped>
.notifications-page {
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

.filter-card {
  margin-bottom: 24px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  border: 1px solid #f0f0f0;
}

.notifications-container {
  min-height: 400px;
}

.notification-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.notification-card {
  background: #fafafa;
  border-radius: 12px;
  border: 1px solid #f0f0f0;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.notification-card.unread {
  background: #fff;
  border-left: 4px solid #667eea;
}

.notification-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  transform: translateY(-1px);
}

.notification-card :deep(.n-card__content) {
  padding: 16px 20px;
}

.card-content {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.card-left {
  flex-shrink: 0;
}

.notification-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.notification-icon.info {
  background: #2080f0;
}

.notification-icon.warning {
  background: #f0a020;
}

.notification-icon.default {
  background: #909399;
}

.card-middle {
  flex: 1;
  min-width: 0;
}

.notification-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #667eea;
}

.notification-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 4px;
}

.notification-content {
  font-size: 14px;
  color: #666;
  line-height: 1.5;
  margin-bottom: 8px;
}

.notification-time {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #999;
}

.card-right {
  flex-shrink: 0;
  padding-top: 4px;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid #f0f0f0;
}

@media (max-width: 768px) {
  .notifications-page {
    padding: 16px;
  }

  .page-title {
    font-size: 28px;
  }
}
</style>
