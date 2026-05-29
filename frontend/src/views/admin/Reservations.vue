<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NSpace,
  NButton,
  NIcon,
  NTag,
  NSpin,
  NEmpty,
  NInput,
  NSelect,
  NPagination,
  NModal,
  useMessage
} from 'naive-ui'
import {
  RefreshOutline,
  DocumentTextOutline,
  TimeOutline,
  PersonOutline,
  BusinessOutline,
  AlertCircleOutline,
  BookOutline,
  TrendingUpOutline,
  FlameOutline
} from '@vicons/ionicons5'
import { fetchReservationPage } from '@/api/reservation'
import { fetchResourceById } from '@/api/resource'
import { fetchResourceSlots } from '@/api/reservation'
import { fetchUserById, fetchUserOverview } from '@/api/user'

const router = useRouter()
const message = useMessage()

const loading = ref(false)
const reservations = ref([])
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const searchKeyword = ref('')
const filterStatus = ref(null)

// 资源详情弹窗
const showResourceModal = ref(false)
const resourceDetail = ref(null)
const resourceSlots = ref([])
const resourceLoading = ref(false)
const resourceError = ref(null)

// 用户详情弹窗
const showUserModal = ref(false)
const userDetail = ref(null)
const userOverview = ref(null)
const userLoading = ref(false)
const userError = ref(null)

const statusOptions = [
  { label: '全部状态', value: null },
  { label: '已预约', value: 'BOOKED' },
  { label: '已取消', value: 'CANCELLED' }
]

const getStatusColor = (status) => {
  const colorMap = { 'BOOKED': 'success', 'CANCELLED': 'default' }
  return colorMap[status] || 'default'
}

const getStatusText = (status) => {
  const textMap = { 'BOOKED': '已预约', 'CANCELLED': '已取消' }
  return textMap[status] || status
}

const getSlotTypeColor = (type) => type === 'HOT' ? 'error' : 'default'
const getSlotTypeText = (type) => type === 'HOT' ? '热门时段' : '普通时段'

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

async function loadReservations() {
  try {
    loading.value = true
    const params = { pageNum: currentPage.value, pageSize: pageSize.value }
    if (searchKeyword.value) params.keyword = searchKeyword.value
    if (filterStatus.value) params.status = filterStatus.value
    const data = await fetchReservationPage(params)
    reservations.value = data.records
    total.value = data.total
  } catch (error) {
    message.error(error.message || '加载预约列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadReservations()
}

function handlePageChange(page) {
  currentPage.value = page
  loadReservations()
}

function handlePageSizeChange(size) {
  pageSize.value = size
  currentPage.value = 1
  loadReservations()
}

function handleViewResource(reservation) {
  // 使用资源名称作为快照信息
  showResourceModal.value = true
  resourceLoading.value = true
  resourceError.value = null
  resourceDetail.value = {
    resourceName: reservation.resourceName,
    resourceCode: reservation.resourceCode,
    location: reservation.location
  }
  resourceSlots.value = []

  // 如果有resourceId，尝试加载资源详情
  if (reservation.resourceId) {
    loadResourceDetail(reservation.resourceId)
  } else {
    // 没有resourceId，显示历史快照
    resourceLoading.value = false
    resourceError.value = 'no_id'
  }
}

async function loadResourceDetail(resourceId) {
  try {
    // 尝试获取资源详情
    const resource = await fetchResourceById(resourceId)
    resourceDetail.value = resource

    // 加载时段列表
    const slots = await fetchResourceSlots(resourceId)
    resourceSlots.value = slots
    resourceLoading.value = false
  } catch (error) {
    // 资源已被删除或其他错误
    resourceLoading.value = false
    if (error.response?.status === 404 || error.message?.includes('不存在')) {
      resourceError.value = 'deleted'
    } else {
      resourceError.value = 'error'
      message.error(error.message || '加载资源详情失败')
    }
  }
}

function handleGoToResource() {
  if (resourceDetail.value?.id) {
    showResourceModal.value = false
    router.push({
      path: '/admin/resources',
      query: { resourceId: resourceDetail.value.id }
    })
  }
}

function handleViewUser(reservation) {
  showUserModal.value = true
  userLoading.value = true
  userError.value = null
  userDetail.value = null
  userOverview.value = null

  // 检查是否有userId
  if (reservation.userId) {
    loadUserDetail(reservation.userId)
  } else {
    userLoading.value = false
    userError.value = 'no_id'
  }
}

async function loadUserDetail(userId) {
  try {
    // 获取用户基本信息
    const user = await fetchUserById(userId)
    userDetail.value = user

    // 获取用户预约统计
    const overview = await fetchUserOverview(userId)
    userOverview.value = overview

    userLoading.value = false
  } catch (error) {
    userLoading.value = false
    if (error.response?.status === 404 || error.message?.includes('不存在')) {
      userError.value = 'deleted'
    } else {
      userError.value = 'error'
      message.error(error.message || '加载用户详情失败')
    }
  }
}

function handleGoToUser() {
  if (userDetail.value?.id) {
    showUserModal.value = false
    router.push({
      path: '/admin/users',
      query: { userId: userDetail.value.id }
    })
  }
}

onMounted(() => {
  loadReservations()
})
</script>

<template>
  <div class="admin-page">
    <div class="page-header">
      <h1 class="page-title">预约管理</h1>
      <p class="page-subtitle">查看所有预约记录</p>
    </div>

    <n-card class="filter-card" :bordered="false">
      <n-space :size="12" align="center" justify="space-between" wrap>
        <n-space :size="12">
          <n-input
            v-model:value="searchKeyword"
            placeholder="搜索预约编号或用户..."
            clearable
            style="width: 240px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <n-select
            v-model:value="filterStatus"
            :options="statusOptions"
            placeholder="预约状态"
            clearable
            style="width: 130px"
            @update:value="handleSearch"
          />
        </n-space>
        <n-button @click="loadReservations">
          <template #icon><n-icon :component="RefreshOutline" /></template>
          刷新
        </n-button>
      </n-space>
    </n-card>

    <div class="list-container">
      <n-spin :show="loading">
        <n-empty v-if="!loading && reservations.length === 0" description="暂无预约记录" style="margin-top: 80px" />

        <div v-else class="reservation-list">
          <n-card v-for="reservation in reservations" :key="reservation.id" class="list-card" :bordered="false">
            <div class="card-content">
              <div class="card-left">
                <div class="reservation-info">
                  <div class="reservation-no">
                    <n-icon :component="DocumentTextOutline" :size="16" />
                    {{ reservation.reservationNo }}
                  </div>
                  <div class="user-info">
                    <n-button
                      text
                      type="primary"
                      style="font-size: 13px"
                      @click="handleViewUser(reservation)"
                    >
                      <n-icon :component="PersonOutline" :size="16" style="margin-right: 4px" />
                      {{ reservation.userNickname || '未知用户' }}
                    </n-button>
                  </div>
                </div>
              </div>

              <div class="card-middle">
                <div class="info-block">
                  <div class="info-label">资源</div>
                  <n-button
                    text
                    type="primary"
                    style="font-weight: 600; font-size: 14px"
                    @click="handleViewResource(reservation)"
                  >
                    {{ reservation.resourceName }}
                  </n-button>
                </div>
                <div class="info-block">
                  <div class="info-label">时间</div>
                  <div class="time-text">{{ formatDateTime(reservation.startDatetime) }}</div>
                  <div class="time-text">{{ formatDateTime(reservation.endDatetime) }}</div>
                </div>
                <div class="info-block">
                  <div class="info-label">类型</div>
                  <n-tag v-if="reservation.sourceType" :type="getSlotTypeColor(reservation.sourceType)" size="small">
                    {{ getSlotTypeText(reservation.sourceType) }}
                  </n-tag>
                </div>
              </div>

              <div class="card-right">
                <div class="status-wrapper">
                  <div class="info-label">状态</div>
                  <n-tag :type="getStatusColor(reservation.status)" size="small" round>
                    {{ getStatusText(reservation.status) }}
                  </n-tag>
                </div>
                <div v-if="reservation.cancelReason" class="cancel-reason">
                  取消原因: {{ reservation.cancelReason }}
                </div>
              </div>
            </div>
          </n-card>
        </div>

        <div v-if="total > 0" class="pagination-wrapper">
          <n-pagination
            v-model:page="currentPage"
            v-model:page-size="pageSize"
            :page-count="Math.ceil(total / pageSize)"
            :page-sizes="[10, 20, 50, 100]"
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
                        {{ formatDateTime(slot.startDatetime) }} - {{ formatDateTime(slot.endDatetime) }}
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
            前往管理
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 用户详情弹窗 -->
    <n-modal
      v-model:show="showUserModal"
      preset="card"
      title="用户详情"
      style="width: 700px"
      :bordered="false"
      segmented
    >
      <n-spin :show="userLoading">
        <!-- 用户已删除 -->
        <div v-if="userError === 'deleted'" style="text-align: center; padding: 40px 0">
          <n-icon :component="AlertCircleOutline" :size="48" style="color: #f0a020; margin-bottom: 16px" />
          <n-text strong style="font-size: 16px; display: block; margin-bottom: 8px">
            该用户已被删除
          </n-text>
          <n-text depth="3" style="font-size: 14px">
            用户信息已不存在
          </n-text>
        </div>

        <!-- 用户ID不存在 -->
        <div v-else-if="userError === 'no_id'" style="text-align: center; padding: 40px 0">
          <n-icon :component="AlertCircleOutline" :size="48" style="color: #909399; margin-bottom: 16px" />
          <n-text strong style="font-size: 16px; display: block; margin-bottom: 8px">
            用户信息缺失
          </n-text>
          <n-text depth="3" style="font-size: 14px">
            早期预约记录未保存用户ID
          </n-text>
        </div>

        <!-- 用户详情 -->
        <div v-else-if="userDetail">
          <n-space vertical :size="16">
            <!-- 基本信息 -->
            <n-card size="small" :bordered="false" style="background: #fafafa">
              <n-space vertical :size="12">
                <n-space align="center" :size="12">
                  <div style="width: 48px; height: 48px; border-radius: 50%; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); display: flex; align-items: center; justify-content: center">
                    <n-icon :component="PersonOutline" :size="24" style="color: #fff" />
                  </div>
                  <div>
                    <n-text strong style="font-size: 16px; display: block">{{ userDetail.nickname }}</n-text>
                    <n-text depth="3" style="font-size: 13px">@{{ userDetail.username }}</n-text>
                  </div>
                </n-space>
                <n-space :size="24">
                  <div>
                    <n-text depth="3" style="font-size: 12px">角色</n-text>
                    <n-tag :type="userDetail.role === 'ADMIN' ? 'error' : 'info'" size="small" style="display: block; margin-top: 4px">
                      {{ userDetail.role === 'ADMIN' ? '管理员' : '普通用户' }}
                    </n-tag>
                  </div>
                  <div>
                    <n-text depth="3" style="font-size: 12px">状态</n-text>
                    <n-tag :type="userDetail.status === 'ACTIVE' ? 'success' : 'warning'" size="small" style="display: block; margin-top: 4px">
                      {{ userDetail.status === 'ACTIVE' ? '激活' : '禁用' }}
                    </n-tag>
                  </div>
                  <div>
                    <n-text depth="3" style="font-size: 12px">电话</n-text>
                    <n-text style="font-size: 14px; display: block; margin-top: 4px">
                      {{ userDetail.phone || '未设置' }}
                    </n-text>
                  </div>
                </n-space>
              </n-space>
            </n-card>

            <!-- 预约统计 -->
            <div v-if="userOverview">
              <n-text strong style="font-size: 14px; margin-bottom: 12px; display: block">
                预约统计
              </n-text>
              <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px">
                <n-card size="small" :bordered="true" style="text-align: center">
                  <n-icon :component="BookOutline" :size="20" style="color: #667eea; margin-bottom: 8px" />
                  <n-text strong style="font-size: 20px; display: block">{{ userOverview.totalReservationCount || 0 }}</n-text>
                  <n-text depth="3" style="font-size: 12px">总预约</n-text>
                </n-card>
                <n-card size="small" :bordered="true" style="text-align: center">
                  <n-icon :component="TimeOutline" :size="20" style="color: #18a058; margin-bottom: 8px" />
                  <n-text strong style="font-size: 20px; display: block">{{ userOverview.activeReservationCount || 0 }}</n-text>
                  <n-text depth="3" style="font-size: 12px">进行中</n-text>
                </n-card>
                <n-card size="small" :bordered="true" style="text-align: center">
                  <n-icon :component="TrendingUpOutline" :size="20" style="color: #2080f0; margin-bottom: 8px" />
                  <n-text strong style="font-size: 20px; display: block">{{ userOverview.recent30DayReservationCount || 0 }}</n-text>
                  <n-text depth="3" style="font-size: 12px">近30天</n-text>
                </n-card>
                <n-card size="small" :bordered="true" style="text-align: center">
                  <n-icon :component="FlameOutline" :size="20" style="color: #f0a020; margin-bottom: 8px" />
                  <n-text strong style="font-size: 20px; display: block">{{ userOverview.finishedReservationCount || 0 }}</n-text>
                  <n-text depth="3" style="font-size: 12px">已完成</n-text>
                </n-card>
              </div>

              <!-- 用户偏好 -->
              <n-card size="small" :bordered="false" style="background: #fafafa; margin-top: 12px">
                <n-text strong style="font-size: 13px; display: block; margin-bottom: 8px">用户偏好</n-text>
                <n-space :size="16">
                  <div>
                    <n-text depth="3" style="font-size: 12px">常用资源</n-text>
                    <n-text style="font-size: 13px; display: block; margin-top: 2px">
                      {{ userOverview.favoriteResourceName || '暂无' }}
                    </n-text>
                  </div>
                  <div>
                    <n-text depth="3" style="font-size: 12px">偏好类型</n-text>
                    <n-text style="font-size: 13px; display: block; margin-top: 2px">
                      {{ userOverview.favoriteResourceType || '暂无' }}
                    </n-text>
                  </div>
                </n-space>
              </n-card>
            </div>
          </n-space>
        </div>
      </n-spin>

      <template #footer>
        <n-space justify="end">
          <n-button @click="showUserModal = false">关闭</n-button>
          <n-button
            v-if="userDetail?.id && !userError"
            type="primary"
            @click="handleGoToUser"
          >
            前往管理
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.admin-page {
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

.list-container {
  min-height: 400px;
}

.reservation-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.list-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  transition: all 0.3s ease;
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

.card-left {
  width: 220px;
  padding-right: 24px;
  border-right: 2px solid #f0f0f0;
}

.reservation-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.reservation-no {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
  font-family: 'Courier New', monospace;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #666;
}

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

.resource-name {
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
}

.time-text {
  font-size: 13px;
  color: #666;
}

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

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid #f0f0f0;
}

@media (max-width: 768px) {
  .admin-page {
    padding: 16px;
  }

  .page-title {
    font-size: 28px;
  }

  .card-content {
    flex-wrap: wrap;
    gap: 16px;
  }

  .card-left {
    width: 100%;
  }

  .card-middle {
    width: 100%;
    order: 3;
  }

  .card-right {
    width: 100%;
    align-items: flex-start;
  }
}
</style>
