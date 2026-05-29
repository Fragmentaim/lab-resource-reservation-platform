<script setup>
import { ref, onMounted, computed } from 'vue'
import {
  NCard,
  NSpace,
  NInput,
  NSelect,
  NButton,
  NIcon,
  NTag,
  NEmpty,
  NSpin,
  NModal,
  useMessage
} from 'naive-ui'
import {
  SearchOutline,
  CarSportOutline,
  BusinessOutline,
  DesktopOutline,
  ConstructOutline,
  LocationOutline,
  TimeOutline,
  FlameOutline,
  CalendarOutline,
  PeopleOutline
} from '@vicons/ionicons5'
import { fetchResourceList } from '@/api/resource'
import { fetchResourceSlots, createReservation } from '@/api/reservation'
import { useRouter } from 'vue-router'

const router = useRouter()
const message = useMessage()

const loading = ref(false)
const resources = ref([])
const searchKeyword = ref('')
const filterType = ref(null)
const filterStatus = ref(null)

// 选中的资源
const selectedResource = ref(null)
const slots = ref([])
const slotsLoading = ref(false)

// 预约确认弹窗
const showConfirmModal = ref(false)
const selectedSlot = ref(null)
const submitting = ref(false)

const typeOptions = [
  { label: '全部类型', value: null },
  { label: '靶车', value: 'TARGET_CAR' },
  { label: '测试场', value: 'TEST_FIELD' },
  { label: '工位', value: 'WORKBENCH' },
  { label: '设备', value: 'DEVICE' }
]

const statusOptions = [
  { label: '全部状态', value: null },
  { label: '可用', value: 'AVAILABLE' },
  { label: '维护中', value: 'MAINTAINING' },
  { label: '禁用', value: 'DISABLED' }
]

const getResourceIcon = (type) => {
  const iconMap = {
    'TARGET_CAR': CarSportOutline,
    'TEST_FIELD': BusinessOutline,
    'WORKBENCH': DesktopOutline,
    'DEVICE': ConstructOutline
  }
  return iconMap[type] || BusinessOutline
}

const getTypeColor = (type) => {
  const colorMap = {
    'TARGET_CAR': '#18a058',
    'TEST_FIELD': '#2080f0',
    'WORKBENCH': '#f0a020',
    'DEVICE': '#8b5cf6'
  }
  return colorMap[type] || '#909399'
}

const getStatusColor = (status) => {
  const colorMap = {
    'AVAILABLE': 'success',
    'MAINTAINING': 'warning',
    'DISABLED': 'default'
  }
  return colorMap[status] || 'default'
}

const getStatusText = (status) => {
  const textMap = {
    'AVAILABLE': '可用',
    'MAINTAINING': '维护中',
    'DISABLED': '禁用'
  }
  return textMap[status] || status
}

// 按类型分组资源
const groupedResources = computed(() => {
  const groups = {}
  resources.value.forEach(resource => {
    const type = resource.resourceType
    if (!groups[type]) {
      groups[type] = []
    }
    groups[type].push(resource)
  })
  return groups
})

async function loadResources() {
  try {
    loading.value = true
    const params = {}
    if (searchKeyword.value) params.name = searchKeyword.value
    if (filterType.value) params.type = filterType.value
    if (filterStatus.value) params.status = filterStatus.value
    resources.value = await fetchResourceList(params)
  } catch (error) {
    message.error(error.message || '加载资源列表失败')
  } finally {
    loading.value = false
  }
}

// 选择资源
async function selectResource(resource) {
  selectedResource.value = resource
  await loadSlots(resource.id)
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
      resourceId: selectedResource.value.id,
      slotId: selectedSlot.value.id
    })

    message.success('预约成功！')
    showConfirmModal.value = false

    // 重新加载时段列表
    await loadSlots(selectedResource.value.id)

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

onMounted(() => {
  loadResources()
})
</script>

<template>
  <div class="resources-page">
    <div class="page-header">
      <h1 class="page-title">资源浏览</h1>
      <p class="page-subtitle">选择您需要的实验室资源进行预约</p>
    </div>

    <n-card class="filter-card" :bordered="false">
      <n-space :size="12" align="center" justify="space-between" wrap>
        <n-input
          v-model:value="searchKeyword"
          placeholder="搜索资源名称、编号或位置..."
          clearable
          style="width: 280px"
          @keyup.enter="loadResources"
          @clear="loadResources"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

        <n-space :size="12">
          <n-select
            v-model:value="filterType"
            :options="typeOptions"
            placeholder="资源类型"
            clearable
            style="width: 130px"
            @update:value="loadResources"
          />
          <n-select
            v-model:value="filterStatus"
            :options="statusOptions"
            placeholder="资源状态"
            clearable
            style="width: 130px"
            @update:value="loadResources"
          />
          <n-button type="primary" @click="loadResources">
            搜索
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <div class="resources-container">
      <!-- 左侧：资源列表 -->
      <div class="resource-panel">
        <n-spin :show="loading">
          <n-empty v-if="!loading && resources.length === 0" description="暂无资源" style="margin-top: 120px" />

          <div v-else class="resource-groups">
            <div v-for="(groupResources, type) in groupedResources" :key="type" class="resource-group">
              <div class="group-header">
                <div class="group-icon" :style="{ background: getTypeColor(type) + '15' }">
                  <n-icon :component="getResourceIcon(type)" :size="18" :style="{ color: getTypeColor(type) }" />
                </div>
                <h3 class="group-title">{{ typeOptions.find(o => o.value === type)?.label || type }}</h3>
                <span class="group-count">{{ groupResources.length }}</span>
              </div>

              <div class="resource-list">
                <div
                  v-for="resource in groupResources"
                  :key="resource.id"
                  class="resource-item"
                  :class="{ active: selectedResource?.id === resource.id }"
                  @click="selectResource(resource)"
                >
                  <div class="resource-info">
                    <div class="resource-name">{{ resource.resourceName }}</div>
                    <div class="resource-meta">
                      <n-icon :component="LocationOutline" :size="14" />
                      <span>{{ resource.location || '未设置' }}</span>
                    </div>
                  </div>
                  <n-tag :type="getStatusColor(resource.status)" size="small" round>
                    {{ getStatusText(resource.status) }}
                  </n-tag>
                </div>
              </div>
            </div>
          </div>
        </n-spin>
      </div>

      <!-- 右侧：时段列表 -->
      <div class="slots-panel">
        <n-spin :show="slotsLoading">
          <div v-if="!selectedResource" class="empty-state">
            <n-icon :component="CalendarOutline" :size="48" style="color: #d0d0d0" />
            <p>请选择左侧资源查看可预约时段</p>
          </div>

          <div v-else-if="slots.length === 0" class="empty-state">
            <n-icon :component="TimeOutline" :size="48" style="color: #d0d0d0" />
            <p>该资源暂无可预约时段</p>
          </div>

          <div v-else class="slots-content">
            <div class="resource-header">
              <div class="resource-icon" :style="{ background: getTypeColor(selectedResource.resourceType) + '15' }">
                <n-icon
                  :component="getResourceIcon(selectedResource.resourceType)"
                  :size="24"
                  :style="{ color: getTypeColor(selectedResource.resourceType) }"
                />
              </div>
              <div class="resource-details">
                <h2 class="resource-title">{{ selectedResource.resourceName }}</h2>
                <div class="resource-meta-info">
                  <span>{{ selectedResource.resourceCode }}</span>
                  <span>·</span>
                  <span>{{ selectedResource.location || '未设置位置' }}</span>
                </div>
              </div>
            </div>

            <div class="slots-list">
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
          </div>
        </n-spin>
      </div>
    </div>

    <!-- 预约确认弹窗 -->
    <n-modal
      v-model:show="showConfirmModal"
      preset="card"
      title="确认预约信息"
      style="width: 500px"
      :bordered="false"
      segmented
    >
      <div v-if="selectedSlot && selectedResource" class="confirm-modal-content">
        <!-- 资源信息卡片 -->
        <div class="confirm-resource-card">
          <div class="confirm-resource-icon" :style="{ background: getTypeColor(selectedResource.resourceType) + '15' }">
            <n-icon
              :component="getResourceIcon(selectedResource.resourceType)"
              :size="32"
              :style="{ color: getTypeColor(selectedResource.resourceType) }"
            />
          </div>
          <div class="confirm-resource-info">
            <h3 class="confirm-resource-name">{{ selectedResource.resourceName }}</h3>
            <div class="confirm-resource-code">{{ selectedResource.resourceCode }}</div>
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
.resources-page {
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

.resources-container {
  display: flex;
  gap: 24px;
  min-height: 600px;
}

/* 左侧资源面板 */
.resource-panel {
  width: 320px;
  flex-shrink: 0;
  background: #fafafa;
  border-radius: 12px;
  padding: 16px;
  border: 1px solid #f0f0f0;
}

.resource-groups {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.resource-group {
  margin-bottom: 0;
}

.group-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e8e8e8;
}

.group-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.group-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0;
}

.group-count {
  font-size: 12px;
  color: #999;
  background: #f0f0f0;
  padding: 2px 8px;
  border-radius: 10px;
}

.resource-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.resource-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px;
  background: #fff;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 2px solid transparent;
}

.resource-item:hover {
  background: #f5f7fa;
  border-color: #667eea40;
}

.resource-item.active {
  background: #667eea10;
  border-color: #667eea;
}

.resource-info {
  flex: 1;
  min-width: 0;
}

.resource-name {
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #999;
}

/* 右侧时段面板 */
.slots-panel {
  flex: 1;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #f0f0f0;
  min-height: 500px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 400px;
  color: #999;
}

.empty-state p {
  margin-top: 16px;
  font-size: 14px;
}

.slots-content {
  padding: 24px;
}

.resource-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
  padding-bottom: 20px;
  border-bottom: 2px solid #f0f0f0;
}

.resource-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.resource-details {
  flex: 1;
}

.resource-title {
  font-size: 22px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 8px 0;
}

.resource-meta-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #999;
}

.slots-list {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.date-group {
  margin-bottom: 0;
}

.date-header {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 12px;
  padding: 8px 16px;
  background: #f5f7fa;
  border-radius: 8px;
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
  margin-bottom: 12px;
}

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

/* 预约确认弹窗 */
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

@media (max-width: 1200px) {
  .resources-container {
    flex-direction: column;
  }

  .resource-panel {
    width: 100%;
  }

  .slot-cards {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .resources-page {
    padding: 16px;
  }

  .page-title {
    font-size: 28px;
  }

  .filter-card :deep(.n-input) {
    width: 100% !important;
  }
}
</style>
