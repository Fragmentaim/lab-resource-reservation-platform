<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NSpace,
  NButton,
  NIcon,
  NTag,
  NSpin,
  NEmpty,
  NModal,
  NInput,
  useMessage
} from 'naive-ui'
import {
  CalendarOutline,
  TimeOutline,
  LocationOutline,
  CloseCircleOutline,
  CheckmarkCircleOutline,
  DocumentTextOutline,
  BookOutline,
  TrendingUpOutline,
  FlameOutline,
  RefreshOutline,
  SearchOutline,
  AlertCircleOutline,
  PersonOutline
} from '@vicons/ionicons5'
import { fetchMyReservations, cancelReservation, fetchResourceSlots } from '@/api/reservation'
import { fetchResourceById } from '@/api/resource'
import { fetchCurrentUser } from '@/api/auth'
import { fetchUserOverview } from '@/api/user'

const router = useRouter()
const message = useMessage()

// 加载状态
const loading = ref(false)

// 预约列表
const reservations = ref([])

// 状态筛选
const filterStatus = ref(null)

// 搜索关键词
const searchKeyword = ref('')

// 资源详情弹窗
const showResourceModal = ref(false)
const resourceDetail = ref(null)
const resourceSlots = ref([])
const resourceLoading = ref(false)
const resourceError = ref(null)

// 取消预约弹窗
const showCancelModal = ref(false)
const selectedReservation = ref(null)
const cancelReason = ref('')
const submitting = ref(false)

// 状态选项
const statusOptions = [
  { label: '全部状态', value: null },
  { label: '已预约', value: 'BOOKED' },
  { label: '已取消', value: 'CANCELLED' }
]

// 获取状态颜色
const getStatusColor = (status) => {
  const colorMap = {
    'BOOKED': 'success',
    'CANCELLED': 'default'
  }
  return colorMap[status] || 'default'
}

// 获取状态文本
const getStatusText = (status) => {
  const textMap = {
    'BOOKED': '已预约',
    'CANCELLED': '已取消'
  }
  return textMap[status] || status
}

// 获取时段类型颜色
const getSourceTypeColor = (type) => {
  return type === 'HOT' ? 'error' : 'default'
}

// 获取时段类型文本
const getSourceTypeText = (type) => {
  return type === 'HOT' ? '热门时段' : '普通时段'
}

// 格式化时间
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

// 格式化短时间
const formatShortDateTime = (datetime) => {
  if (!datetime) return '-'
  const date = new Date(datetime)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hours}:${minutes}`
}

// 筛选后的预约列表
const filteredReservations = computed(() => {
  let list = reservations.value

  if (filterStatus.value) {
    list = list.filter(r => r.status === filterStatus.value)
  }

  if (searchKeyword.value) {
    const keyword = searchKeyword.value.toLowerCase()
    list = list.filter(r =>
      (r.reservationNo && r.reservationNo.toLowerCase().includes(keyword)) ||
      (r.resourceName && r.resourceName.toLowerCase().includes(keyword)) ||
      (r.resourceCode && r.resourceCode.toLowerCase().includes(keyword))
    )
  }

  return list
})

// 预约统计
const reservationStats = computed(() => {
  const total = reservations.value.length
  const active = reservations.value.filter(r => r.status === 'BOOKED').length
  const recent30Days = reservations.value.filter(r => {
    if (!r.createdAt) return false
    const created = new Date(r.createdAt)
    const now = new Date()
    const diffDays = (now - created) / (1000 * 60 * 60 * 24)
    return diffDays <= 30
  }).length

  return { total, active, recent30Days }
})

// 加载预约列表
async function loadReservations() {
  try {
    loading.value = true
    reservations.value = await fetchMyReservations()
  } catch (error) {
    message.error(error.message || '加载预约列表失败')
  } finally {
    loading.value = false
  }
}

// 查看资源详情弹窗
function handleViewResource(reservation) {
  showResourceModal.value = true
  resourceLoading.value = true
  resourceError.value = null
  resourceDetail.value = {
    resourceName: reservation.resourceName,
    resourceCode: reservation.resourceCode,
    location: reservation.location
  }
  resourceSlots.value = []

  if (reservation.resourceId) {
    loadResourceDetail(reservation.resourceId)
  } else {
    resourceLoading.value = false
    resourceError.value = 'no_id'
  }
}

async function loadResourceDetail(resourceId) {
  try {
    const resource = await fetchResourceById(resourceId)
    resourceDetail.value = resource

    const slots = await fetchResourceSlots(resourceId)
    resourceSlots.value = slots
    resourceLoading.value = false
  } catch (error) {
    resourceLoading.value = false
    if (error.response?.status === 404 || error.message?.includes('不存在')) {
      resourceError.value = 'deleted'
    } else {
      resourceError.value = 'error'
      message.error(error.message || '加载资源详情失败')
    }
  }
}

// 跳转到资源浏览页面
function handleGoToResource() {
  if (resourceDetail.value?.id) {
    showResourceModal.value = false
    router.push(`/resources/${resourceDetail.value.id}`)
  }
}

// 打开取消预约弹窗
function openCancelModal(reservation) {
  selectedReservation.value = reservation
  cancelReason.value = ''
  showCancelModal.value = true
}

// 确认取消预约
async function confirmCancel() {
  if (!selectedReservation.value) return

  try {
    submitting.value = true
    await cancelReservation(selectedReservation.value.id, {
      cancelReason: cancelReason.value.trim() || null
    })

    message.success('取消预约成功')
    showCancelModal.value = false

    await loadReservations()
  } catch (error) {
    message.error(error.message || '取消预约失败')
  } finally {
    submitting.value = false
  }
}

// 初始化
onMounted(() => {
  loadReservations()
})
</script>

<template>
  <div class="my-reservations-page">
    <div class="page-header">
      <h1 class="page-title">我的预约</h1>
      <p class="page-subtitle">查看和管理您的预约记录</p>
    </div>

    <!-- 预约统计概览 -->
    <div class="reservation-stats">
      <div class="stat-item">
        <div class="stat-icon" style="background: #667eea15;">
          <n-icon :component="BookOutline" :size="20" style="color: #667eea;" />
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ reservationStats.active }}</div>
          <div class="stat-label">进行中</div>
        </div>
      </div>
      <div class="stat-item">
        <div class="stat-icon" style="background: #18a05815;">
          <n-icon :component="CalendarOutline" :size="20" style="color: #18a058;" />
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ reservationStats.total }}</div>
          <div class="stat-label">总预约</div>
        </div>
      </div>
      <div class="stat-item">
        <div class="stat-icon" style="background: #2080f015;">
          <n-icon :component="TrendingUpOutline" :size="20" style="color: #2080f0;" />
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ reservationStats.recent30Days }}</div>
          <div class="stat-label">近30天</div>
        </div>
      </div>
    </div>

    <!-- 筛选栏 -->
    <n-card class="filter-card" :bordered="false">
      <n-space :size="12" align="center" justify="space-between" wrap>
        <n-space :size="12">
          <n-input
            v-model:value="searchKeyword"
            placeholder="搜索编号或资源名称..."
            clearable
            style="width: 240px"
          >
            <template #prefix>
              <n-icon :component="SearchOutline" />
            </template>
          </n-input>
          <n-select
            v-model:value="filterStatus"
            :options="statusOptions"
            placeholder="预约状态"
            clearable
            style="width: 130px"
          />
        </n-space>
        <n-button @click="loadReservations">
          <template #icon><n-icon :component="RefreshOutline" /></template>
          刷新
        </n-button>
      </n-space>
    </n-card>

    <!-- 预约列表 -->
    <div class="list-container">
      <n-spin :show="loading">
        <n-empty v-if="!loading && filteredReservations.length === 0" description="暂无预约记录" style="margin-top: 80px" />

        <div v-else class="reservation-list">
          <n-card
            v-for="reservation in filteredReservations"
            :key="reservation.id"
            class="list-card"
            :bordered="false"
          >
            <div class="card-content">
              <!-- 左侧：预约编号 -->
              <div class="card-left">
                <div class="reservation-no">
                  <n-icon :component="DocumentTextOutline" :size="16" />
                  {{ reservation.reservationNo }}
                </div>
                <div class="created-info">
                  <n-icon :component="CalendarOutline" :size="14" />
                  {{ formatShortDateTime(reservation.createdAt) }}
                </div>
              </div>

              <!-- 中间：资源、时间、类型 -->
              <div class="card-middle">
                <div class="info-block">
                  <div class="info-label">资源</div>
                  <n-button
                    text
                    type="primary"
                    style="font-weight: 600; font-size: 14px"
                    @click="handleViewResource(reservation)"
                  >
                    {{ reservation.resourceName || '资源已删除' }}
                  </n-button>
                </div>
                <div class="info-block">
                  <div class="info-label">时间</div>
                  <div class="time-text">{{ formatShortDateTime(reservation.startDatetime) }}</div>
                  <div class="time-text">{{ formatShortDateTime(reservation.endDatetime) }}</div>
                </div>
                <div class="info-block">
                  <div class="info-label">类型</div>
                  <n-tag v-if="reservation.sourceType" :type="getSourceTypeColor(reservation.sourceType)" size="small">
                    <template #icon v-if="reservation.sourceType === 'HOT'">
                      <n-icon :component="FlameOutline" />
                    </template>
                    {{ getSourceTypeText(reservation.sourceType) }}
                  </n-tag>
                  <span v-else class="no-type">-</span>
                </div>
              </div>

              <!-- 右侧：状态和操作 -->
              <div class="card-right">
                <div class="status-wrapper">
                  <div class="info-label">状态</div>
                  <n-tag :type="getStatusColor(reservation.status)" size="small" round>
                    {{ getStatusText(reservation.status) }}
                  </n-tag>
                </div>
                <div v-if="reservation.status === 'CANCELLED' && reservation.cancelReason" class="cancel-reason">
                  原因: {{ reservation.cancelReason }}
                </div>
                <n-button
                  v-if="reservation.status === 'BOOKED'"
                  type="error"
                  text
                  size="small"
                  @click="openCancelModal(reservation)"
                >
                  <template #icon>
                    <n-icon :component="CloseCircleOutline" />
                  </template>
                  取消
                </n-button>
              </div>
            </div>
          </n-card>
        </div>
      </n-spin>
    </div>

    <!-- 资源详情弹窗 -->
    <n-modal
      v-model:show="showResourceModal"
      preset="card"
      title="资源详情"
      style="width: 700px"
      :bordered="false"
      segmented
    >
      <n-spin :show="resourceLoading">
        <!-- 资源已删除 -->
        <div v-if="resourceError === 'deleted'" style="text-align: center; padding: 40px 0">
          <n-icon :component="AlertCircleOutline" :size="48" style="color: #f0a020; margin-bottom: 16px" />
          <n-text strong style="font-size: 16px; display: block; margin-bottom: 8px">
            该资源已被删除
          </n-text>
          <n-text depth="3" style="font-size: 14px">
            以下是预约时的资源快照信息
          </n-text>
        </div>

        <!-- 资源ID不存在 -->
        <div v-else-if="resourceError === 'no_id'" style="text-align: center; padding: 40px 0">
          <n-icon :component="AlertCircleOutline" :size="48" style="color: #909399; margin-bottom: 16px" />
          <n-text strong style="font-size: 16px; display: block; margin-bottom: 8px">
            历史资源信息
          </n-text>
          <n-text depth="3" style="font-size: 14px">
            早期预约记录未保存资源ID
          </n-text>
        </div>

        <!-- 资源详情 -->
        <div v-else-if="resourceDetail">
          <n-space vertical :size="16">
            <!-- 基本信息 -->
            <n-card size="small" :bordered="false" style="background: #fafafa">
              <n-space vertical :size="12">
                <div>
                  <n-text depth="3" style="font-size: 12px">资源名称</n-text>
                  <n-text strong style="font-size: 15px; display: block; margin-top: 4px">
                    {{ resourceDetail.resourceName }}
                  </n-text>
                </div>
                <n-space :size="24">
                  <div>
                    <n-text depth="3" style="font-size: 12px">资源编号</n-text>
                    <n-text style="font-size: 14px; display: block; margin-top: 4px; font-family: monospace">
                      {{ resourceDetail.resourceCode || '-' }}
                    </n-text>
                  </div>
                  <div>
                    <n-text depth="3" style="font-size: 12px">所在位置</n-text>
                    <n-text style="font-size: 14px; display: block; margin-top: 4px">
                      {{ resourceDetail.location || '未设置' }}
                    </n-text>
                  </div>
                </n-space>
              </n-space>
            </n-card>

            <!-- 时段列表 -->
            <div v-if="resourceSlots.length > 0">
              <n-text strong style="font-size: 14px; margin-bottom: 12px; display: block">
                可预约时段 ({{ resourceSlots.length }})
              </n-text>
              <n-space vertical :size="8">
                <n-card
                  v-for="slot in resourceSlots"
                  :key="slot.id"
                  size="small"
                  :bordered="true"
                  style="background: #fff"
                >
                  <n-space justify="space-between" align="center">
                    <div>
                      <n-text strong style="font-size: 13px">
                        {{ formatShortDateTime(slot.startDatetime) }} - {{ formatShortDateTime(slot.endDatetime) }}
                      </n-text>
                      <n-space :size="8" style="margin-top: 4px">
                        <n-tag :type="slot.slotType === 'HOT' ? 'error' : 'default'" size="small">
                          {{ slot.slotType === 'HOT' ? '热门' : '普通' }}
                        </n-tag>
                        <n-text depth="3" style="font-size: 12px">
                          剩余 {{ slot.remainQuota || 0 }} / {{ slot.totalQuota }}
                        </n-text>
                      </n-space>
                    </div>
                    <n-tag :type="slot.status === 'OPEN' ? 'success' : 'default'" size="small">
                      {{ slot.status === 'OPEN' ? '开放' : '关闭' }}
                    </n-tag>
                  </n-space>
                </n-card>
              </n-space>
            </div>

            <n-empty v-else description="暂无可用时段" style="margin: 20px 0" />
          </n-space>
        </div>
      </n-spin>

      <template #footer>
        <n-space justify="end">
          <n-button @click="showResourceModal = false">关闭</n-button>
          <n-button
            v-if="resourceDetail?.id && !resourceError"
            type="primary"
            @click="handleGoToResource"
          >
            前往浏览
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 取消预约弹窗 -->
    <n-modal
      v-model:show="showCancelModal"
      preset="card"
      title="取消预约"
      style="width: 480px"
      :bordered="false"
      segmented
    >
      <div v-if="selectedReservation" class="cancel-modal-content">
        <!-- 预约信息 -->
        <div class="cancel-info-card">
          <div class="cancel-info-item">
            <span class="cancel-info-label">资源名称</span>
            <span class="cancel-info-value">{{ selectedReservation.resourceName }}</span>
          </div>
          <div class="cancel-info-item">
            <span class="cancel-info-label">时段时间</span>
            <span class="cancel-info-value">
              {{ formatShortDateTime(selectedReservation.startDatetime) }} - {{ formatShortDateTime(selectedReservation.endDatetime) }}
            </span>
          </div>
        </div>

        <!-- 取消原因 -->
        <div class="cancel-reason-input">
          <div class="cancel-reason-label">取消原因（选填）</div>
          <n-input
            v-model:value="cancelReason"
            type="textarea"
            placeholder="请输入取消原因..."
            :rows="3"
            maxlength="200"
            show-count
          />
        </div>

        <!-- 提示信息 -->
        <div class="cancel-notice">
          <n-icon :component="CheckmarkCircleOutline" :size="16" />
          取消后名额将释放给其他用户
        </div>
      </div>

      <template #footer>
        <n-space justify="end">
          <n-button @click="showCancelModal = false">暂不取消</n-button>
          <n-button type="error" :loading="submitting" @click="confirmCancel">
            确认取消
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.my-reservations-page {
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

/* 预约统计概览 */
.reservation-stats {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
}

.stat-item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: #fafafa;
  border-radius: 12px;
  border: 1px solid #f0f0f0;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.stat-item:hover {
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
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

/* 筛选卡片 */
.filter-card {
  margin-bottom: 24px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  border: 1px solid #f0f0f0;
}

/* 列表容器 */
.list-container {
  min-height: 400px;
}

/* 预约列表 */
.reservation-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 列表卡片 - 管理员端风格 */
.list-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  border: 1px solid #f0f0f0;
}

.list-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  transform: translateY(-2px);
}

.list-card :deep(.n-card__content) {
  padding: 16px 20px;
}

.card-content {
  display: flex;
  align-items: center;
  gap: 0;
}

/* 左侧 */
.card-left {
  width: 200px;
  padding-right: 24px;
  border-right: 2px solid #f0f0f0;
}

.reservation-no {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
  font-family: 'Courier New', monospace;
  margin-bottom: 6px;
}

.created-info {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #999;
}

/* 中间 */
.card-middle {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 0;
  padding: 0 24px;
}

.info-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: 160px;
}

.info-label {
  font-size: 12px;
  color: #999;
}

.time-text {
  font-size: 13px;
  color: #666;
}

.no-type {
  font-size: 13px;
  color: #ccc;
}

/* 右侧 */
.card-right {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding-left: 24px;
  border-left: 2px solid #f0f0f0;
  min-width: 120px;
}

.status-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.cancel-reason {
  font-size: 12px;
  color: #999;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 取消预约弹窗 */
.cancel-modal-content {
  padding: 8px 0;
}

.cancel-info-card {
  padding: 20px;
  background: #f5f7fa;
  border-radius: 12px;
  margin-bottom: 24px;
}

.cancel-info-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
}

.cancel-info-item:not(:last-child) {
  border-bottom: 1px solid #e8e8e8;
}

.cancel-info-label {
  font-size: 14px;
  color: #666;
  font-weight: 500;
}

.cancel-info-value {
  font-size: 15px;
  color: #1a1a1a;
  font-weight: 600;
}

.cancel-reason-input {
  margin-bottom: 24px;
}

.cancel-reason-label {
  font-size: 15px;
  color: #1a1a1a;
  font-weight: 600;
  margin-bottom: 12px;
}

.cancel-notice {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px;
  background: #f0f9ff;
  border-left: 4px solid #2080f0;
  border-radius: 8px;
  font-size: 14px;
  color: #1e88e5;
  font-weight: 500;
}

/* 响应式 */
@media (max-width: 768px) {
  .my-reservations-page {
    padding: 16px;
  }

  .page-title {
    font-size: 28px;
  }

  .reservation-stats {
    flex-direction: column;
  }

  .card-content {
    flex-wrap: wrap;
    gap: 16px;
  }

  .card-left {
    width: 100%;
    border-right: none;
  }

  .card-middle {
    width: 100%;
    order: 3;
  }

  .card-right {
    width: 100%;
    align-items: flex-start;
    border-left: none;
  }
}
</style>
