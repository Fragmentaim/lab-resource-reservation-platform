<script setup>
import { ref, onMounted } from 'vue'
import {
  NCard,
  NSpace,
  NIcon,
  NTag,
  NSpin,
  NEmpty,
  NButton,
  NGrid,
  NGi,
  useMessage
} from 'naive-ui'
import {
  BookOutline,
  CalendarOutline,
  CheckmarkCircleOutline,
  CloseCircleOutline,
  BusinessOutline,
  TimeOutline,
  FlameOutline,
  NotificationsOutline,
  PersonOutline,
  TrendingUpOutline,
  SpeedometerOutline,
  AlertCircleOutline,
  RefreshOutline,
  DocumentTextOutline
} from '@vicons/ionicons5'
import { fetchAdminDashboard } from '@/api/dashboard'

const message = useMessage()
const loading = ref(false)
const dashboard = ref(null)

async function loadDashboard() {
  try {
    loading.value = true
    dashboard.value = await fetchAdminDashboard()
  } catch (error) {
    message.error(error.message || '加载仪表盘失败')
  } finally {
    loading.value = false
  }
}

function getPressureColor(level) {
  return { HIGH: 'error', MEDIUM: 'warning', LOW: 'success' }[level] || 'default'
}

function getPressureText(level) {
  return { HIGH: '高压', MEDIUM: '中等', LOW: '正常' }[level] || '未知'
}

function getOccupancyPercent(slot) {
  if (!slot.totalQuota) return 0
  return Math.round(((slot.totalQuota - slot.remainQuota) / slot.totalQuota) * 100)
}

function getOccupancyColor(percent) {
  if (percent >= 90) return '#f56c6c'
  if (percent >= 60) return '#f0a020'
  return '#18a058'
}

const formatDateTime = (datetime) => {
  if (!datetime) return '-'
  const date = new Date(datetime)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hours}:${minutes}`
}

const getRequestStatusColor = (status) => {
  return { PENDING: 'warning', PROCESSING: 'info', COMPLETED: 'success', FAILED: 'error' }[status] || 'default'
}

onMounted(() => {
  loadDashboard()
})
</script>

<template>
  <div class="overview-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">系统概览</h1>
        <p class="page-subtitle">实验室预约系统实时监控</p>
      </div>
      <n-button @click="loadDashboard">
        <template #icon><n-icon :component="RefreshOutline" /></template>
        刷新
      </n-button>
    </div>

    <n-spin :show="loading">
      <div v-if="dashboard" class="dashboard-content">
        <!-- 核心指标 -->
        <div class="section-title">
          <n-icon :component="SpeedometerOutline" :size="20" />
          预约统计
        </div>
        <n-grid :cols="4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
          <n-gi span="4 m:2 l:1">
            <div class="metric-card" style="border-left: 4px solid #667eea">
              <div class="metric-icon" style="background: #667eea15">
                <n-icon :component="BookOutline" :size="22" style="color: #667eea" />
              </div>
              <div class="metric-info">
                <div class="metric-value">{{ dashboard.totalReservationCount || 0 }}</div>
                <div class="metric-label">预约总数</div>
              </div>
            </div>
          </n-gi>
          <n-gi span="4 m:2 l:1">
            <div class="metric-card" style="border-left: 4px solid #18a058">
              <div class="metric-icon" style="background: #18a05815">
                <n-icon :component="CalendarOutline" :size="22" style="color: #18a058" />
              </div>
              <div class="metric-info">
                <div class="metric-value">{{ dashboard.todayReservationCount || 0 }}</div>
                <div class="metric-label">今日新增</div>
              </div>
            </div>
          </n-gi>
          <n-gi span="4 m:2 l:1">
            <div class="metric-card" style="border-left: 4px solid #2080f0">
              <div class="metric-icon" style="background: #2080f015">
                <n-icon :component="TimeOutline" :size="22" style="color: #2080f0" />
              </div>
              <div class="metric-info">
                <div class="metric-value">{{ dashboard.activeReservationCount || 0 }}</div>
                <div class="metric-label">活跃预约</div>
              </div>
            </div>
          </n-gi>
          <n-gi span="4 m:2 l:1">
            <div class="metric-card" style="border-left: 4px solid #f0a020">
              <div class="metric-icon" style="background: #f0a02015">
                <n-icon :component="CheckmarkCircleOutline" :size="22" style="color: #f0a020" />
              </div>
              <div class="metric-info">
                <div class="metric-value">{{ dashboard.finishedReservationCount || 0 }}</div>
                <div class="metric-label">已完成</div>
              </div>
            </div>
          </n-gi>
        </n-grid>

        <!-- 资源与时段 -->
        <div class="section-title" style="margin-top: 32px">
          <n-icon :component="BusinessOutline" :size="20" />
          资源与时段
        </div>
        <n-grid :cols="4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
          <n-gi span="4 m:2 l:1">
            <div class="metric-card" style="border-left: 4px solid #8b5cf6">
              <div class="metric-icon" style="background: #8b5cf615">
                <n-icon :component="BusinessOutline" :size="22" style="color: #8b5cf6" />
              </div>
              <div class="metric-info">
                <div class="metric-value">{{ dashboard.totalResourceCount || 0 }}</div>
                <div class="metric-label">资源总数</div>
              </div>
            </div>
          </n-gi>
          <n-gi span="4 m:2 l:1">
            <div class="metric-card" style="border-left: 4px solid #18a058">
              <div class="metric-icon" style="background: #18a05815">
                <n-icon :component="CheckmarkCircleOutline" :size="22" style="color: #18a058" />
              </div>
              <div class="metric-info">
                <div class="metric-value">{{ dashboard.availableResourceCount || 0 }}</div>
                <div class="metric-label">可用资源</div>
              </div>
            </div>
          </n-gi>
          <n-gi span="4 m:2 l:1">
            <div class="metric-card" style="border-left: 4px solid #2080f0">
              <div class="metric-icon" style="background: #2080f015">
                <n-icon :component="TimeOutline" :size="22" style="color: #2080f0" />
              </div>
              <div class="metric-info">
                <div class="metric-value">{{ dashboard.openSlotCount || 0 }}</div>
                <div class="metric-label">开放时段</div>
              </div>
            </div>
          </n-gi>
          <n-gi span="4 m:2 l:1">
            <div class="metric-card" style="border-left: 4px solid #f56c6c">
              <div class="metric-icon" style="background: #f56c6c15">
                <n-icon :component="FlameOutline" :size="22" style="color: #f56c6c" />
              </div>
              <div class="metric-info">
                <div class="metric-value">{{ dashboard.hotOpenSlotCount || 0 }}</div>
                <div class="metric-label">热门时段</div>
              </div>
            </div>
          </n-gi>
        </n-grid>

        <!-- 系统状态 -->
        <div class="section-title" style="margin-top: 32px">
          <n-icon :component="AlertCircleOutline" :size="20" />
          系统状态
        </div>
        <div class="status-grid">
          <div class="status-item">
            <div class="status-dot" :class="{ warning: dashboard.pendingAsyncRequestCount > 0 }"></div>
            <div class="status-text">
              <span class="status-value">{{ dashboard.pendingAsyncRequestCount || 0 }}</span>
              <span class="status-label">待处理请求</span>
            </div>
          </div>
          <div class="status-item">
            <div class="status-dot" :class="{ warning: dashboard.dispatchPendingRequestCount > 0 }"></div>
            <div class="status-text">
              <span class="status-value">{{ dashboard.dispatchPendingRequestCount || 0 }}</span>
              <span class="status-label">待派发</span>
            </div>
          </div>
          <div class="status-item">
            <div class="status-dot" :class="{ error: dashboard.failedAsyncRequestCount > 0 }"></div>
            <div class="status-text">
              <span class="status-value">{{ dashboard.failedAsyncRequestCount || 0 }}</span>
              <span class="status-label">失败请求</span>
            </div>
          </div>
          <div class="status-item">
            <div class="status-dot" :class="{ warning: dashboard.pendingReminderCount > 0 }"></div>
            <div class="status-text">
              <span class="status-value">{{ dashboard.pendingReminderCount || 0 }}</span>
              <span class="status-label">待发送提醒</span>
            </div>
          </div>
          <div class="status-item">
            <div class="status-dot"></div>
            <div class="status-text">
              <span class="status-value">{{ dashboard.unreadNotificationCount || 0 }}</span>
              <span class="status-label">未读通知</span>
            </div>
          </div>
          <div class="status-item">
            <div class="status-dot" :class="{ warning: dashboard.pendingAuditOutboxCount > 0 }"></div>
            <div class="status-text">
              <span class="status-value">{{ dashboard.pendingAuditOutboxCount || 0 }}</span>
              <span class="status-label">待发送审计</span>
            </div>
          </div>
        </div>

        <!-- 底部三栏：热门时段 / 资源热度 / 最近请求 -->
        <n-grid :cols="1" :x-gap="24" :y-gap="24" responsive="screen" item-responsive style="margin-top: 32px">
          <!-- 热门时段实时状态 -->
          <n-gi span="1 l:2">
            <div class="section-title">
              <n-icon :component="FlameOutline" :size="20" />
              热门时段实时状态
            </div>
            <n-empty v-if="!dashboard.hotSlots || dashboard.hotSlots.length === 0" description="暂无热门时段数据" style="margin: 40px 0" />
            <div v-else class="hot-slot-list">
              <n-card v-for="slot in dashboard.hotSlots" :key="slot.slotId" class="hot-slot-card" :bordered="false">
                <div class="slot-main">
                  <div class="slot-resource">
                    <div class="slot-resource-name">{{ slot.resourceName }}</div>
                    <div class="slot-resource-code">{{ slot.resourceCode }}</div>
                  </div>
                  <div class="slot-time">{{ formatDateTime(slot.startDatetime) }} - {{ formatDateTime(slot.endDatetime) }}</div>
                </div>
                <div class="slot-quota-bar">
                  <div class="quota-info">
                    <span>已约 {{ slot.bookedQuota || 0 }} / {{ slot.totalQuota }}</span>
                    <span class="quota-percent" :style="{ color: getOccupancyColor(getOccupancyPercent(slot)) }">
                      {{ getOccupancyPercent(slot) }}%
                    </span>
                  </div>
                  <div class="progress-track">
                    <div
                      class="progress-fill"
                      :style="{
                        width: getOccupancyPercent(slot) + '%',
                        background: getOccupancyColor(getOccupancyPercent(slot))
                      }"
                    ></div>
                  </div>
                </div>
                <n-tag :type="getPressureColor(slot.pressureLevel)" size="small" round>
                  {{ getPressureText(slot.pressureLevel) }}
                </n-tag>
              </n-card>
            </div>
          </n-gi>

          <!-- 最近异步请求 -->
          <n-gi span="1 l:2">
            <div class="section-title">
              <n-icon :component="DocumentTextOutline" :size="20" />
              最近预约请求
            </div>
            <n-empty v-if="!dashboard.recentRequests || dashboard.recentRequests.length === 0" description="暂无请求记录" style="margin: 40px 0" />
            <div v-else class="request-list">
              <div v-for="req in dashboard.recentRequests" :key="req.requestNo" class="request-item">
                <div class="request-main">
                  <div class="request-no">{{ req.requestNo }}</div>
                  <div class="request-user">{{ req.username }}</div>
                </div>
                <div class="request-resource">{{ req.resourceName }}</div>
                <n-tag :type="getRequestStatusColor(req.status)" size="small">
                  {{ req.status }}
                </n-tag>
              </div>
            </div>

            <!-- 资源热度排行 -->
            <div class="section-title" style="margin-top: 32px">
              <n-icon :component="TrendingUpOutline" :size="20" />
              资源热度排行（近7天）
            </div>
            <n-empty v-if="!dashboard.topResources || dashboard.topResources.length === 0" description="暂无数据" style="margin: 40px 0" />
            <div v-else class="top-resource-list">
              <div v-for="(res, index) in dashboard.topResources" :key="res.resourceId" class="top-resource-item">
                <div class="rank-badge" :class="{ top3: index < 3 }">{{ index + 1 }}</div>
                <div class="resource-info">
                  <div class="resource-name">{{ res.resourceName }}</div>
                  <div class="resource-code">{{ res.resourceCode }}</div>
                </div>
                <div class="resource-stats">
                  <span class="stat-primary">{{ res.reservationCount || 0 }} 预约</span>
                  <span class="stat-sub">{{ res.activeReservationCount || 0 }} 活跃</span>
                </div>
              </div>
            </div>
          </n-gi>
        </n-grid>
      </div>
    </n-spin>
  </div>
</template>

<style scoped>
.overview-page {
  min-height: 100%;
  background: #fff;
  padding: 24px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 32px;
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

/* 区域标题 */
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 16px;
}

/* 指标卡片 */
.metric-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: #fafafa;
  border-radius: 12px;
  border: 1px solid #f0f0f0;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.metric-card:hover {
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}

.metric-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.metric-info {
  flex: 1;
}

.metric-value {
  font-size: 28px;
  font-weight: 700;
  color: #1a1a1a;
  line-height: 1;
  margin-bottom: 4px;
}

.metric-label {
  font-size: 13px;
  color: #999;
}

/* 系统状态网格 */
.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: #fafafa;
  border-radius: 10px;
  border: 1px solid #f0f0f0;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #18a058;
  flex-shrink: 0;
}

.status-dot.warning {
  background: #f0a020;
}

.status-dot.error {
  background: #f56c6c;
}

.status-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.status-value {
  font-size: 20px;
  font-weight: 700;
  color: #1a1a1a;
  line-height: 1;
}

.status-label {
  font-size: 12px;
  color: #999;
}

/* 热门时段列表 */
.hot-slot-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.hot-slot-card {
  background: #fafafa;
  border-radius: 10px;
  border: 1px solid #f0f0f0;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.hot-slot-card:hover {
  background: #fff;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}

.hot-slot-card :deep(.n-card__content) {
  padding: 16px 20px;
  display: flex;
  align-items: center;
  gap: 20px;
}

.slot-main {
  flex: 1;
  min-width: 0;
}

.slot-resource-name {
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 2px;
}

.slot-resource-code {
  font-size: 12px;
  color: #999;
  font-family: 'Courier New', monospace;
}

.slot-time {
  font-size: 13px;
  color: #666;
  margin-top: 4px;
}

.slot-quota-bar {
  width: 200px;
  flex-shrink: 0;
}

.quota-info {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #666;
  margin-bottom: 6px;
}

.quota-percent {
  font-weight: 600;
}

.progress-track {
  height: 6px;
  background: #f0f0f0;
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.5s cubic-bezier(0.4, 0, 0.2, 1);
}

/* 最近请求列表 */
.request-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.request-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  transition: background 0.2s;
}

.request-item:hover {
  background: #f5f7fa;
}

.request-main {
  min-width: 160px;
}

.request-no {
  font-size: 13px;
  font-weight: 600;
  color: #1a1a1a;
  font-family: 'Courier New', monospace;
}

.request-user {
  font-size: 12px;
  color: #999;
}

.request-resource {
  flex: 1;
  font-size: 13px;
  color: #666;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 资源热度排行 */
.top-resource-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.top-resource-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  transition: background 0.2s;
}

.top-resource-item:hover {
  background: #f5f7fa;
}

.rank-badge {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #e0e0e0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  color: #666;
  flex-shrink: 0;
}

.rank-badge.top3 {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
}

.resource-info {
  flex: 1;
  min-width: 0;
}

.resource-name {
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-code {
  font-size: 12px;
  color: #999;
  font-family: 'Courier New', monospace;
}

.resource-stats {
  display: flex;
  gap: 12px;
  flex-shrink: 0;
}

.stat-primary {
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
}

.stat-sub {
  font-size: 13px;
  color: #999;
}

@media (max-width: 768px) {
  .overview-page {
    padding: 16px;
  }

  .page-title {
    font-size: 28px;
  }

  .status-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
