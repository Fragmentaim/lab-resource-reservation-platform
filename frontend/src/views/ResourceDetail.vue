<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NCard,
  NSpace,
  NButton,
  NIcon,
  NTag,
  NSpin,
  NEmpty,
  NDescriptions,
  NDescriptionsItem,
  NModal,
  useMessage
} from 'naive-ui'
import {
  ArrowBackOutline,
  CarSportOutline,
  BusinessOutline,
  DesktopOutline,
  ConstructOutline,
  LocationOutline,
  TimeOutline,
  PeopleOutline,
  CalendarOutline,
  FlameOutline
} from '@vicons/ionicons5'
import { fetchResourceById } from '@/api/resource'
import { fetchResourceSlots, createReservation } from '@/api/reservation'

const route = useRoute()
const router = useRouter()
const message = useMessage()

// 加载状态
const loading = ref(false)
const slotsLoading = ref(false)

// 资源信息
const resource = ref(null)

// 时段列表
const slots = ref([])

// 预约确认弹窗
const showConfirmModal = ref(false)
const selectedSlot = ref(null)
const submitting = ref(false)

// 获取资源图标
const getResourceIcon = (type) => {
  const iconMap = {
    'TARGET_CAR': CarSportOutline,
    'TEST_FIELD': BusinessOutline,
    'WORKBENCH': DesktopOutline,
    'DEVICE': ConstructOutline
  }
  return iconMap[type] || BusinessOutline
}

// 获取资源类型颜色
const getTypeColor = (type) => {
  const colorMap = {
    'TARGET_CAR': '#18a058',
    'TEST_FIELD': '#2080f0',
    'WORKBENCH': '#f0a020',
    'DEVICE': '#8b5cf6'
  }
  return colorMap[type] || '#909399'
}

// 获取状态颜色
const getStatusColor = (status) => {
  const colorMap = {
    'AVAILABLE': 'success',
    'MAINTAINING': 'warning',
    'DISABLED': 'default'
  }
  return colorMap[status] || 'default'
}

// 获取状态文本
const getStatusText = (status) => {
  const textMap = {
    'AVAILABLE': '可用',
    'MAINTAINING': '维护中',
    'DISABLED': '禁用'
  }
  return textMap[status] || status
}

// 格式化时间
const formatDateTime = (datetime) => {
  if (!datetime) return '-'
  const date = new Date(datetime)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hours}:${minutes}`
}

// 格式化日期
const formatDate = (datetime) => {
  if (!datetime) return '-'
  const date = new Date(datetime)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const weekDays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const weekDay = weekDays[date.getDay()]
  return `${year}年${month}月${day}日 ${weekDay}`
}

// 按日期分组时段
const groupedSlots = computed(() => {
  if (!slots.value || slots.value.length === 0) return {}

  const groups = {}
  slots.value.forEach(slot => {
    const dateKey = formatDate(slot.startDatetime)
    if (!groups[dateKey]) {
      groups[dateKey] = []
    }
    groups[dateKey].push(slot)
  })

  return groups
})

// 加载资源信息
async function loadResource() {
  try {
    loading.value = true
    const id = route.params.id
    if (!id) {
      message.error('资源ID不存在')
      router.back()
      return
    }

    resource.value = await fetchResourceById(id)

    // 如果资源不可用，提示用户
    if (resource.value.status !== 'AVAILABLE') {
      message.warning('当前资源不可预约')
    }

    // 加载时段列表
    await loadSlots(id)
  } catch (error) {
    message.error(error.message || '加载资源信息失败')
    router.back()
  } finally {
    loading.value = false
  }
}

// 加载时段列表
async function loadSlots(resourceId) {
  try {
    slotsLoading.value = true
    slots.value = await fetchResourceSlots(resourceId)
  } catch (error) {
    message.error(error.message || '加载时段列表失败')
  } finally {
    slotsLoading.value = false
  }
}

// 选择时段预约
function selectSlot(slot) {
  if (slot.remainQuota <= 0) {
    message.warning('该时段名额已满')
    return
  }

  // 检查热门时段是否已开放
  if (slot.slotType === 'HOT' && slot.openTime) {
    const now = new Date()
    const openTime = new Date(slot.openTime)
    if (now < openTime) {
      message.warning(`热门时段将于 ${formatDateTime(slot.openTime)} 开放预约`)
      return
    }
  }

  selectedSlot.value = slot
  showConfirmModal.value = true
}

// 确认预约
async function confirmReservation() {
  if (!selectedSlot.value) return

  try {
    submitting.value = true
    await createReservation({
      resourceId: resource.value.id,
      slotId: selectedSlot.value.id
    })

    message.success('预约成功！')
    showConfirmModal.value = false

    // 重新加载时段列表
    await loadSlots(resource.value.id)

    // 跳转到我的预约页面
    setTimeout(() => {
      router.push('/my-reservations')
    }, 1000)
  } catch (error) {
    message.error(error.message || '预约失败，请重试')
  } finally {
    submitting.value = false
  }
}

// 返回上一页
function goBack() {
  router.back()
}

// 初始化
onMounted(() => {
  loadResource()
})
</script>

<template>
  <div class="resource-detail-page">
    <!-- 加载状态 -->
    <n-spin :show="loading" description="加载中...">
      <div v-if="resource" class="content">
        <!-- 返回按钮 -->
        <div class="back-bar">
          <n-button text @click="goBack">
            <template #icon>
              <n-icon :component="ArrowBackOutline" />
            </template>
            返回资源列表
          </n-button>
        </div>

        <!-- 资源信息卡片 -->
        <n-card class="resource-card" :bordered="false">
          <div class="resource-header">
            <div class="icon-wrapper" :style="{ background: getTypeColor(resource.resourceType) + '15' }">
              <n-icon
                :component="getResourceIcon(resource.resourceType)"
                :size="40"
                :style="{ color: getTypeColor(resource.resourceType) }"
              />
            </div>
            <div class="resource-info">
              <h1 class="resource-name">{{ resource.resourceName }}</h1>
              <div class="resource-meta">
                <n-tag :type="getStatusColor(resource.status)" size="small" round>
                  {{ getStatusText(resource.status) }}
                </n-tag>
                <span class="resource-code">{{ resource.resourceCode }}</span>
              </div>
            </div>
          </div>

          <!-- 资源详细信息 - 美化版 -->
          <div class="resource-details-beautiful">
            <div class="detail-item">
              <div class="detail-icon" :style="{ background: getTypeColor(resource.resourceType) + '10' }">
                <n-icon :component="getResourceIcon(resource.resourceType)" :size="20" :style="{ color: getTypeColor(resource.resourceType) }" />
              </div>
              <div class="detail-content">
                <div class="detail-label">资源类型</div>
                <div class="detail-value">{{ resource.resourceTypeDesc }}</div>
              </div>
            </div>

            <div class="detail-item">
              <div class="detail-icon" style="background: #18a05810">
                <n-icon :component="LocationOutline" :size="20" style="color: #18a058" />
              </div>
              <div class="detail-content">
                <div class="detail-label">所在位置</div>
                <div class="detail-value">{{ resource.location || '未设置' }}</div>
              </div>
            </div>

            <div class="detail-item detail-item-full">
              <div class="detail-icon" style="background: #2080f010">
                <n-icon :component="ConstructOutline" :size="20" style="color: #2080f0" />
              </div>
              <div class="detail-content">
                <div class="detail-label">资源描述</div>
                <div class="detail-value">{{ resource.description || '暂无描述' }}</div>
              </div>
            </div>
          </div>
        </n-card>

        <!-- 时段列表 -->
        <div class="slots-section">
          <h2 class="section-title">
            <n-icon :component="CalendarOutline" :size="24" />
            可预约时段
          </h2>

          <n-spin :show="slotsLoading">
            <!-- 空状态 -->
            <n-empty
              v-if="!slotsLoading && slots.length === 0"
              description="暂无可预约时段"
              style="margin-top: 60px"
            />

            <!-- 时段列表 -->
            <div v-else class="slots-list">
              <div
                v-for="(dateSlots, dateKey) in groupedSlots"
                :key="dateKey"
                class="date-group"
              >
                <div class="date-header">{{ dateKey }}</div>
                <div class="slot-cards">
                  <n-card
                    v-for="slot in dateSlots"
                    :key="slot.id"
                    class="slot-card"
                    :bordered="false"
                    hoverable
                    @click="selectSlot(slot)"
                  >
                    <div class="slot-header">
                      <div class="slot-time">
                        <n-icon :component="TimeOutline" :size="18" />
                        {{ formatDateTime(slot.startDatetime) }} - {{ formatDateTime(slot.endDatetime) }}
                      </div>
                      <n-tag
                        v-if="slot.slotType === 'HOT'"
                        type="error"
                        size="small"
                        round
                      >
                        <template #icon>
                          <n-icon :component="FlameOutline" />
                        </template>
                        热门
                      </n-tag>
                    </div>

                    <div class="slot-quota">
                      <div class="quota-progress">
                        <div class="progress-bar">
                          <div
                            class="progress-fill"
                            :style="{ width: (slot.totalQuota > 0 ? slot.remainQuota / slot.totalQuota * 100 : 0) + '%' }"
                          ></div>
                        </div>
                        <span class="quota-text" :class="{ 'quota-warning': slot.remainQuota <= 2 }">
                          {{ slot.remainQuota }}/{{ slot.totalQuota }} 可预约
                        </span>
                      </div>
                    </div>

                    <div class="slot-action">
                      <n-button
                        type="primary"
                        size="small"
                        :disabled="slot.remainQuota <= 0"
                      >
                        {{ slot.remainQuota > 0 ? '立即预约' : '已满' }}
                      </n-button>
                    </div>
                  </n-card>
                </div>
              </div>
            </div>
          </n-spin>
        </div>
      </div>
    </n-spin>

    <!-- 预约确认弹窗 -->
    <n-modal
      v-model:show="showConfirmModal"
      preset="card"
      title="确认预约信息"
      style="width: 500px"
      :bordered="false"
      segmented
    >
      <div v-if="selectedSlot && resource" class="confirm-modal-content">
        <!-- 资源信息卡片 -->
        <div class="confirm-resource-card">
          <div class="confirm-resource-icon" :style="{ background: getTypeColor(resource.resourceType) + '15' }">
            <n-icon
              :component="getResourceIcon(resource.resourceType)"
              :size="32"
              :style="{ color: getTypeColor(resource.resourceType) }"
            />
          </div>
          <div class="confirm-resource-info">
            <h3 class="confirm-resource-name">{{ resource.resourceName }}</h3>
            <div class="confirm-resource-code">{{ resource.resourceCode }}</div>
          </div>
        </div>

        <!-- 预约详情 -->
        <div class="confirm-details">
          <div class="confirm-detail-item">
            <div class="confirm-detail-label">
              <n-icon :component="TimeOutline" :size="16" />
              时段时间
            </div>
            <div class="confirm-detail-value">
              {{ formatDateTime(selectedSlot.startDatetime) }} - {{ formatDateTime(selectedSlot.endDatetime) }}
            </div>
          </div>

          <div class="confirm-detail-item">
            <div class="confirm-detail-label">
              <n-icon :component="selectedSlot.slotType === 'HOT' ? FlameOutline : CalendarOutline" :size="16" />
              时段类型
            </div>
            <div class="confirm-detail-value">
              <n-tag :type="selectedSlot.slotType === 'HOT' ? 'error' : 'default'" size="small">
                {{ selectedSlot.slotType === 'HOT' ? '热门时段' : '普通时段' }}
              </n-tag>
            </div>
          </div>

          <div class="confirm-detail-item">
            <div class="confirm-detail-label">
              <n-icon :component="PeopleOutline" :size="16" />
              剩余名额
            </div>
            <div class="confirm-detail-value">
              <span class="quota-number">{{ selectedSlot.remainQuota }}</span>
              <span class="quota-total"> / {{ selectedSlot.totalQuota }} 个</span>
            </div>
          </div>
        </div>

        <!-- 提示信息 -->
        <div class="confirm-notice">
          <n-icon :component="CalendarOutline" :size="16" />
          预约成功后可在"我的预约"中查看
        </div>
      </div>

      <template #footer>
        <n-space justify="end">
          <n-button @click="showConfirmModal = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="confirmReservation">
            确认预约
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.resource-detail-page {
  min-height: 100%;
  padding-bottom: 40px;
  background: #fff;
  padding-top: 24px;
}

.content {
  max-width: 1200px;
  margin: 0 auto;
}

/* 返回栏 */
.back-bar {
  margin-bottom: 20px;
}

/* 资源卡片 */
.resource-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  margin-bottom: 24px;
}

.resource-header {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 24px;
  padding-bottom: 24px;
  border-bottom: 1px solid #f0f0f0;
}

.icon-wrapper {
  width: 80px;
  height: 80px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.resource-info {
  flex: 1;
}

.resource-name {
  font-size: 28px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 12px 0;
  letter-spacing: -0.5px;
}

.resource-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}

.resource-code {
  font-size: 13px;
  color: #909399;
  font-family: 'Courier New', monospace;
  background: #f5f7fa;
  padding: 4px 12px;
  border-radius: 4px;
}

/* 资源详细信息 - 美化版 */
.resource-details-beautiful {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-top: 8px;
}

.detail-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  transition: all 0.3s ease;
}

.detail-item:hover {
  background: #f5f7fa;
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
}

.detail-item-full {
  grid-column: 1 / -1;
}

.detail-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.detail-content {
  flex: 1;
}

.detail-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
  font-weight: 500;
}

.detail-value {
  font-size: 15px;
  color: #1a1a1a;
  font-weight: 500;
  line-height: 1.6;
}

/* 确认预约弹窗 */
.confirm-modal-content {
  padding: 8px 0;
}

.confirm-resource-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: #fafafa;
  border-radius: 12px;
  margin-bottom: 20px;
}

.confirm-resource-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.confirm-resource-info {
  flex: 1;
}

.confirm-resource-name {
  font-size: 18px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 4px 0;
}

.confirm-resource-code {
  font-size: 12px;
  color: #909399;
  font-family: 'Courier New', monospace;
}

.confirm-details {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 20px;
}

.confirm-detail-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 8px;
}

.confirm-detail-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #606266;
  font-weight: 500;
}

.confirm-detail-value {
  font-size: 14px;
  color: #1a1a1a;
  font-weight: 500;
}

.quota-number {
  font-size: 20px;
  font-weight: 600;
  color: #18a058;
}

.quota-total {
  color: #909399;
}

.confirm-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  background: #fff7e6;
  border-left: 3px solid #f0a020;
  border-radius: 4px;
  font-size: 13px;
  color: #8b6914;
}

/* 时段区域 */
.slots-section {
  margin-top: 32px;
}

.section-title {
  font-size: 20px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 20px 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 时段列表 */
.slots-list {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.date-group {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.date-header {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 2px solid #f0f0f0;
}

.slot-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.slot-card {
  background: #fafafa;
  border-radius: 8px;
  transition: all 0.3s ease;
  cursor: pointer;
  position: relative;
  overflow: hidden;
}

.slot-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 4px;
  height: 100%;
  background: linear-gradient(180deg, #667eea 0%, #764ba2 100%);
  opacity: 0;
  transition: opacity 0.3s ease;
}

.slot-card:hover {
  background: #fff;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  transform: translateY(-2px);
}

.slot-card:hover::before {
  opacity: 1;
}

.slot-card :deep(.n-card__content) {
  padding: 16px;
}

.slot-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.slot-time {
  font-size: 14px;
  font-weight: 500;
  color: #1a1a1a;
  display: flex;
  align-items: center;
  gap: 6px;
}

.slot-quota {
  font-size: 13px;
  color: #606266;
  margin-bottom: 12px;
}

/* 名额进度条 */
.quota-progress {
  display: flex;
  align-items: center;
  gap: 12px;
}

.progress-bar {
  flex: 1;
  height: 6px;
  background: #f0f0f0;
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #18a058 0%, #36ad6a 100%);
  transition: width 0.3s ease;
  border-radius: 3px;
}

.quota-text {
  font-size: 13px;
  color: #666;
  white-space: nowrap;
  font-weight: 500;
}

.quota-warning {
  color: #f56c6c;
  font-weight: 600;
}

.slot-action {
  display: flex;
  justify-content: flex-end;
}

/* 确认弹窗内容 */
.confirm-content {
  padding: 12px 0;
}

.confirm-content p {
  margin: 8px 0;
  font-size: 14px;
  color: #606266;
}

/* 响应式 */
@media (max-width: 768px) {
  .resource-name {
    font-size: 24px;
  }

  .icon-wrapper {
    width: 60px;
    height: 60px;
  }

  .slot-cards {
    grid-template-columns: 1fr;
  }
}
</style>
