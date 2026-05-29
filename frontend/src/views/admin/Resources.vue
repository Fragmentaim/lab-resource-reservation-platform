<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  NCard,
  NSpace,
  NButton,
  NIcon,
  NTag,
  NSpin,
  NEmpty,
  NModal,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NSelect,
  NDatePicker,
  NSwitch,
  useMessage,
  useDialog
} from 'naive-ui'
import {
  AddOutline,
  TrashOutline,
  CreateOutline,
  RefreshOutline,
  CarSportOutline,
  BusinessOutline,
  DesktopOutline,
  ConstructOutline,
  TimeOutline,
  FlameOutline
} from '@vicons/ionicons5'
import { fetchResourcePage, createResource, updateResource, deleteResource } from '@/api/resource'
import { fetchResourceSlots } from '@/api/reservation'
import { createSlot, updateSlot, deleteSlot } from '@/api/slot'
import { useDictStore } from '@/store/dict'
import { debounce } from 'lodash-es'

const message = useMessage()
const dialog = useDialog()
const dictStore = useDictStore()
const route = useRoute()

// 资源列表
const resources = ref([])
const resourceLoading = ref(false)

// 选中的资源
const selectedResource = ref(null)

// 时段列表
const slots = ref([])
const slotLoading = ref(false)

// 弹窗
const showResourceModal = ref(false)
const showSlotModal = ref(false)
const resourceModalType = ref('add')
const slotModalType = ref('add')
const resourceFormRef = ref(null)
const slotFormRef = ref(null)
const submitting = ref(false)

// 资源表单
const resourceFormData = ref({
  id: null,
  resourceCode: '',
  resourceName: '',
  resourceType: 'TARGET_CAR',
  status: 'AVAILABLE',
  location: '',
  description: ''
})

// 时段表单
const slotFormData = ref({
  id: null,
  resourceId: null,
  startDatetime: null,
  endDatetime: null,
  slotType: 'NORMAL',
  openTime: null,
  totalQuota: 10,
  status: 'OPEN'
})

// 字典数据
const resourceTypeDict = ref([])
const typeOptions = ref([])

const statusOptions = [
  { label: '可用', value: 'AVAILABLE' },
  { label: '维护中', value: 'MAINTAINING' },
  { label: '禁用', value: 'DISABLED' }
]

const slotTypeOptions = [
  { label: '普通时段', value: 'NORMAL' },
  { label: '热门时段', value: 'HOT' }
]

const slotStatusOptions = [
  { label: '开放', value: 'OPEN' },
  { label: '关闭', value: 'CLOSED' }
]

const resourceRules = {
  resourceCode: [{ required: true, message: '请输入资源编号', trigger: 'blur' }],
  resourceName: [{ required: true, message: '请输入资源名称', trigger: 'blur' }],
  resourceType: [{ required: true, message: '请选择资源类型', trigger: 'change' }],
  status: [{ required: true, message: '请选择资源状态', trigger: 'change' }]
}

const slotRules = {
  startDatetime: [{ required: true, type: 'number', message: '请选择开始时间', trigger: 'change' }],
  endDatetime: [{ required: true, type: 'number', message: '请选择结束时间', trigger: 'change' }],
  slotType: [{ required: true, message: '请选择时段类型', trigger: 'change' }],
  totalQuota: [{ required: true, type: 'number', message: '请输入总名额', trigger: 'blur' }],
  status: [{ required: true, message: '请选择时段状态', trigger: 'change' }]
}

const getTypeIcon = (type) => {
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

const getTypeName = (type) => {
  const item = resourceTypeDict.value.find(d => d.dictValue === type)
  return item?.dictLabel || type
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

const getSlotTypeColor = (type) => type === 'HOT' ? 'error' : 'default'
const getSlotTypeText = (type) => type === 'HOT' ? '热门' : '普通'
const getSlotStatusColor = (status) => status === 'OPEN' ? 'success' : 'default'
const getSlotStatusText = (status) => status === 'OPEN' ? '开放' : '关闭'

const formatDateTime = (datetime) => {
  if (!datetime) return '-'
  const date = new Date(datetime)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hours}:${minutes}`
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

// 加载字典数据
async function loadDictData() {
  try {
    resourceTypeDict.value = await dictStore.getDictData('resource_type')
    typeOptions.value = resourceTypeDict.value.map(item => ({
      label: item.dictLabel,
      value: item.dictValue
    }))
  } catch (error) {
    console.error('加载字典数据失败', error)
  }
}

// 加载资源列表
async function loadResources() {
  try {
    resourceLoading.value = true
    const data = await fetchResourcePage({ pageNum: 1, pageSize: 100 })
    resources.value = data.records

    // 默认选中第一个资源
    if (resources.value.length > 0 && !selectedResource.value) {
      selectResource(resources.value[0])
    }

    // 如果有选中的资源，更新其时段数量
    if (selectedResource.value) {
      const updated = resources.value.find(r => r.id === selectedResource.value.id)
      if (updated) {
        selectedResource.value = updated
      }
    }
  } catch (error) {
    message.error(error.message || '加载资源列表失败')
  } finally {
    resourceLoading.value = false
  }
}

// 加载时段列表
async function loadSlots(resourceId) {
  try {
    slotLoading.value = true
    slots.value = await fetchResourceSlots(resourceId)
  } catch (error) {
    message.error(error.message || '加载时段列表失败')
  } finally {
    slotLoading.value = false
  }
}

// 选择资源
function selectResource(resource) {
  selectedResource.value = resource
  loadSlots(resource.id)
}

// 资源操作
function openAddResourceModal() {
  resourceModalType.value = 'add'
  resourceFormData.value = {
    id: null,
    resourceCode: '',
    resourceName: '',
    resourceType: 'TARGET_CAR',
    status: 'AVAILABLE',
    location: '',
    description: ''
  }
  showResourceModal.value = true
}

function openEditResourceModal(resource) {
  resourceModalType.value = 'edit'
  resourceFormData.value = { ...resource }
  showResourceModal.value = true
}

async function handleResourceSubmit() {
  try {
    await resourceFormRef.value?.validate()
  } catch {
    return
  }

  try {
    submitting.value = true
    if (resourceModalType.value === 'add') {
      await createResource(resourceFormData.value)
      message.success('添加成功')
    } else {
      await updateResource(resourceFormData.value)
      message.success('更新成功')
    }
    showResourceModal.value = false
    await loadResources()
  } catch (error) {
    message.error(error.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

function handleDeleteResource(resource) {
  dialog.warning({
    title: '确认删除',
    content: `确定要删除资源 "${resource.resourceName}" 吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteResource(resource.id)
        message.success('删除成功')
        if (selectedResource.value?.id === resource.id) {
          selectedResource.value = null
          slots.value = []
        }
        await loadResources()
      } catch (error) {
        message.error(error.message || '删除失败')
      }
    }
  })
}

// 时段操作
function openAddSlotModal() {
  if (!selectedResource.value) {
    message.warning('请先选择资源')
    return
  }
  slotModalType.value = 'add'
  slotFormData.value = {
    id: null,
    resourceId: selectedResource.value.id,
    startDatetime: null,
    endDatetime: null,
    slotType: 'NORMAL',
    openTime: null,
    totalQuota: 10,
    status: 'OPEN'
  }
  showSlotModal.value = true
}

function openEditSlotModal(slot) {
  slotModalType.value = 'edit'
  slotFormData.value = {
    ...slot,
    startDatetime: new Date(slot.startDatetime).getTime(),
    endDatetime: new Date(slot.endDatetime).getTime(),
    openTime: slot.openTime ? new Date(slot.openTime).getTime() : null
  }
  showSlotModal.value = true
}

async function handleSlotSubmit() {
  try {
    await slotFormRef.value?.validate()
  } catch {
    return
  }

  if (slotFormData.value.startDatetime >= slotFormData.value.endDatetime) {
    message.error('结束时间必须大于开始时间')
    return
  }

  try {
    submitting.value = true
    const submitData = {
      ...slotFormData.value,
      startDatetime: new Date(slotFormData.value.startDatetime).toISOString(),
      endDatetime: new Date(slotFormData.value.endDatetime).toISOString(),
      openTime: slotFormData.value.openTime ? new Date(slotFormData.value.openTime).toISOString() : null
    }

    if (slotModalType.value === 'add') {
      await createSlot(submitData)
      message.success('添加成功')
    } else {
      await updateSlot(submitData)
      message.success('更新成功')
    }
    showSlotModal.value = false
    await loadSlots(selectedResource.value.id)
    await loadResources()
  } catch (error) {
    message.error(error.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

function handleDeleteSlot(slot) {
  dialog.warning({
    title: '确认删除',
    content: '确定要删除该时段吗？',
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteSlot(slot.id)
        message.success('删除成功')
        await loadSlots(selectedResource.value.id)
        await loadResources()
      } catch (error) {
        message.error(error.message || '删除失败')
      }
    }
  })
}

// 切换时段状态
async function handleToggleSlotStatus(slot) {
  const newStatus = slot.status === 'OPEN' ? 'CLOSED' : 'OPEN'
  try {
    await updateSlot({
      ...slot,
      status: newStatus
    })
    message.success(newStatus === 'OPEN' ? '已开放' : '已关闭')
    await loadSlots(selectedResource.value.id)
  } catch (error) {
    const errorMsg = error.response?.data?.message || error.message || '操作失败'
    message.error(`状态切换失败：${errorMsg}`)
    if (errorMsg.includes('预约')) {
      message.warning('该时段已有预约记录，无法关闭')
    }
  }
}

// 防抖保存名额
const handleQuotaChange = debounce(async (slot) => {
  try {
    await updateSlot({
      ...slot,
      totalQuota: slot.totalQuota
    })
    message.success('名额已更新')
    await loadSlots(selectedResource.value.id)
  } catch (error) {
    message.error(error.message || '更新失败')
  }
}, 500)

onMounted(async () => {
  await loadDictData()
  await loadResources()

  // 检查是否有跳转参数
  const resourceId = route.query.resourceId
  if (resourceId) {
    const targetResource = resources.value.find(r => r.id === Number(resourceId))
    if (targetResource) {
      selectResource(targetResource)
    }
  }
})
</script>

<template>
  <div class="resource-manage-page">
    <div class="page-header">
      <div class="header-content">
        <h1 class="page-title">资源与时段管理</h1>
        <p class="page-subtitle">管理实验室资源及其预约时段</p>
      </div>
      <n-space :size="12">
        <n-button type="primary" @click="openAddResourceModal">
          <template #icon><n-icon :component="AddOutline" /></template>
          添加资源
        </n-button>
        <n-button @click="loadResources">
          <template #icon><n-icon :component="RefreshOutline" /></template>
          刷新
        </n-button>
      </n-space>
    </div>

    <div class="manage-container">
      <!-- 左侧：资源列表 -->
      <div class="resource-panel">
        <n-spin :show="resourceLoading">
          <n-empty v-if="!resourceLoading && resources.length === 0" description="暂无资源" style="margin-top: 120px" />

          <div v-else class="resource-groups">
            <div v-for="(groupResources, type) in groupedResources" :key="type" class="resource-group">
              <div class="group-header">
                <div class="group-icon" :style="{ background: getTypeColor(type) + '15' }">
                  <n-icon :component="getTypeIcon(type)" :size="18" :style="{ color: getTypeColor(type) }" />
                </div>
                <h3 class="group-title">{{ getTypeName(type) }}</h3>
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
                    <div class="resource-code">{{ resource.resourceCode }}</div>
                  </div>
                  <n-tag :type="getStatusColor(resource.status)" size="tiny" round>
                    {{ getStatusText(resource.status) }}
                  </n-tag>
                </div>
              </div>
            </div>
          </div>
        </n-spin>
      </div>

      <!-- 右侧：时段列表 -->
      <div class="slot-panel">
        <div v-if="!selectedResource" class="empty-state">
          <n-icon :component="TimeOutline" :size="64" style="color: #d0d0d0" />
          <p class="empty-text">请从左侧选择一个资源</p>
        </div>

        <template v-else>
          <div class="slot-header">
            <div class="slot-header-left">
              <div class="resource-badge" :style="{ background: getTypeColor(selectedResource.resourceType) + '15' }">
                <n-icon :component="getTypeIcon(selectedResource.resourceType)" :size="24" :style="{ color: getTypeColor(selectedResource.resourceType) }" />
              </div>
              <div class="slot-header-info">
                <h2 class="slot-title">{{ selectedResource.resourceName }}</h2>
                <div class="slot-meta">
                  <span>{{ selectedResource.resourceCode }}</span>
                  <span>·</span>
                  <span>{{ selectedResource.location || '未设置位置' }}</span>
                </div>
              </div>
            </div>
            <n-space :size="8">
              <n-button type="primary" @click="openAddSlotModal">
                <template #icon><n-icon :component="AddOutline" /></template>
                添加时段
              </n-button>
              <n-button @click="openEditResourceModal(selectedResource)">
                <template #icon><n-icon :component="CreateOutline" /></template>
                编辑资源
              </n-button>
              <n-button type="error" @click="handleDeleteResource(selectedResource)">
                <template #icon><n-icon :component="TrashOutline" /></template>
              </n-button>
            </n-space>
          </div>

          <n-spin :show="slotLoading">
            <div class="slot-content">
              <n-empty v-if="!slotLoading && slots.length === 0" description="暂无时段" style="margin-top: 80px" />

              <div v-else class="slot-grid">
                <div v-for="slot in slots" :key="slot.id" class="slot-card">
                  <div class="slot-card-header">
                    <div class="slot-time">
                      <n-icon :component="TimeOutline" :size="18" />
                      <span>{{ formatDateTime(slot.startDatetime) }}</span>
                      <span class="time-separator">-</span>
                      <span>{{ formatDateTime(slot.endDatetime) }}</span>
                    </div>
                    <n-space :size="6">
                      <n-tag :type="getSlotTypeColor(slot.slotType)" size="small" round>
                        <template v-if="slot.slotType === 'HOT'" #icon>
                          <n-icon :component="FlameOutline" />
                        </template>
                        {{ getSlotTypeText(slot.slotType) }}
                      </n-tag>
                      <n-tag :type="getSlotStatusColor(slot.status)" size="small" round>
                        {{ getSlotStatusText(slot.status) }}
                      </n-tag>
                    </n-space>
                  </div>

                  <div class="slot-card-body">
                    <div class="quota-item">
                      <span class="quota-label">剩余名额</span>
                      <span class="quota-value">{{ slot.remainQuota || 0 }}</span>
                    </div>
                    <div class="quota-item">
                      <span class="quota-label">总名额</span>
                      <n-input-number
                        v-model:value="slot.totalQuota"
                        :min="0"
                        :max="9999"
                        :step="1"
                        :show-button="true"
                        size="small"
                        style="width: 100px"
                        @update:value="handleQuotaChange(slot)"
                      >
                        <template #suffix>
                          <n-text depth="3" style="font-size: 12px">个</n-text>
                        </template>
                      </n-input-number>
                    </div>
                  </div>

                  <div class="slot-card-footer">
                    <n-space :size="8" align="center">
                      <n-switch
                        :value="slot.status === 'OPEN'"
                        size="small"
                        @update:value="handleToggleSlotStatus(slot)"
                      >
                        <template #checked>开放</template>
                        <template #unchecked>关闭</template>
                      </n-switch>
                      <n-button size="small" @click="openEditSlotModal(slot)">
                        <template #icon><n-icon :component="CreateOutline" /></template>
                        编辑
                      </n-button>
                      <n-button size="small" type="error" @click="handleDeleteSlot(slot)">
                        <template #icon><n-icon :component="TrashOutline" /></template>
                        删除
                      </n-button>
                    </n-space>
                  </div>
                </div>
              </div>
            </div>
          </n-spin>
        </template>
      </div>
    </div>

    <!-- 资源弹窗 -->
    <n-modal v-model:show="showResourceModal" preset="card" :title="resourceModalType === 'add' ? '添加资源' : '编辑资源'" style="width: 520px" :bordered="false" segmented>
      <n-form ref="resourceFormRef" :model="resourceFormData" :rules="resourceRules" label-placement="left" label-width="80">
        <n-form-item label="资源编号" path="resourceCode">
          <n-input v-model:value="resourceFormData.resourceCode" placeholder="请输入资源编号" />
        </n-form-item>
        <n-form-item label="资源名称" path="resourceName">
          <n-input v-model:value="resourceFormData.resourceName" placeholder="请输入资源名称" />
        </n-form-item>
        <n-form-item label="资源类型" path="resourceType">
          <n-select v-model:value="resourceFormData.resourceType" :options="typeOptions" placeholder="请选择资源类型" />
        </n-form-item>
        <n-form-item label="资源状态" path="status">
          <n-select v-model:value="resourceFormData.status" :options="statusOptions" placeholder="请选择资源状态" />
        </n-form-item>
        <n-form-item label="所在位置" path="location">
          <n-input v-model:value="resourceFormData.location" placeholder="请输入所在位置" />
        </n-form-item>
        <n-form-item label="资源描述" path="description">
          <n-input v-model:value="resourceFormData.description" type="textarea" placeholder="请输入资源描述" :rows="3" />
        </n-form-item>
      </n-form>

      <template #footer>
        <n-space justify="end">
          <n-button @click="showResourceModal = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="handleResourceSubmit">
            {{ resourceModalType === 'add' ? '添加' : '保存' }}
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 时段弹窗 -->
    <n-modal v-model:show="showSlotModal" preset="card" :title="slotModalType === 'add' ? '添加时段' : '编辑时段'" style="width: 520px" :bordered="false" segmented>
      <n-form ref="slotFormRef" :model="slotFormData" :rules="slotRules" label-placement="left" label-width="80">
        <n-form-item label="开始时间" path="startDatetime">
          <n-date-picker v-model:value="slotFormData.startDatetime" type="datetime" placeholder="请选择开始时间" style="width: 100%" />
        </n-form-item>
        <n-form-item label="结束时间" path="endDatetime">
          <n-date-picker v-model:value="slotFormData.endDatetime" type="datetime" placeholder="请选择结束时间" style="width: 100%" />
        </n-form-item>
        <n-form-item label="时段类型" path="slotType">
          <n-select v-model:value="slotFormData.slotType" :options="slotTypeOptions" placeholder="请选择时段类型" />
        </n-form-item>
        <n-form-item v-if="slotFormData.slotType === 'HOT'" label="开放时间" path="openTime">
          <n-date-picker v-model:value="slotFormData.openTime" type="datetime" placeholder="请选择开放预约时间" style="width: 100%" />
        </n-form-item>
        <n-form-item label="总名额" path="totalQuota">
          <n-input-number v-model:value="slotFormData.totalQuota" :min="0" placeholder="请输入总名额" style="width: 100%" />
        </n-form-item>
        <n-form-item label="时段状态" path="status">
          <n-select v-model:value="slotFormData.status" :options="slotStatusOptions" placeholder="请选择时段状态" />
        </n-form-item>
      </n-form>

      <template #footer>
        <n-space justify="end">
          <n-button @click="showSlotModal = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="handleSlotSubmit">
            {{ slotModalType === 'add' ? '添加' : '保存' }}
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.resource-manage-page {
  min-height: 100vh;
  background: #f5f7fa;
  padding: 0;
}

.page-header {
  background: #fff;
  padding: 24px 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8e8e8;
}

.header-content {
  flex: 1;
}

.page-title {
  font-size: 32px;
  font-weight: 700;
  color: #1a1a1a;
  margin: 0 0 8px 0;
  letter-spacing: -1px;
}

.page-subtitle {
  font-size: 14px;
  color: #666;
  margin: 0;
}

.manage-container {
  display: flex;
  gap: 0;
  height: calc(100vh - 120px);
}

/* 左侧资源面板 */
.resource-panel {
  width: 320px;
  background: #fff;
  border-right: 1px solid #e8e8e8;
  overflow-y: auto;
}

.resource-groups {
  padding: 16px;
}

.resource-group {
  margin-bottom: 24px;
}

.resource-group:last-child {
  margin-bottom: 0;
}

.group-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: #fafafa;
  border-radius: 8px;
  margin-bottom: 8px;
}

.group-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.group-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0;
  flex: 1;
}

.group-count {
  font-size: 13px;
  color: #999;
  background: #f0f0f0;
  padding: 2px 8px;
  border-radius: 10px;
}

.resource-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.resource-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 1px solid transparent;
}

.resource-item:hover {
  background: #f5f7fa;
}

.resource-item.active {
  background: #667eea;
  border-color: #667eea;
}

.resource-item.active .resource-name,
.resource-item.active .resource-code {
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
  margin-bottom: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-code {
  font-size: 12px;
  color: #999;
  font-family: 'Courier New', monospace;
}

/* 右侧时段面板 */
.slot-panel {
  flex: 1;
  background: #fff;
  overflow-y: auto;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.empty-text {
  font-size: 14px;
  color: #999;
  margin-top: 16px;
}

.slot-header {
  padding: 24px 32px;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.slot-header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.resource-badge {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.slot-header-info {
  flex: 1;
}

.slot-title {
  font-size: 20px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 4px 0;
}

.slot-meta {
  font-size: 13px;
  color: #999;
}

.slot-content {
  padding: 24px 32px;
}

.slot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.slot-card {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e8e8e8;
  transition: all 0.2s ease;
}

.slot-card:hover {
  border-color: #667eea;
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.1);
}

.slot-card-header {
  padding: 16px;
  border-bottom: 1px solid #f0f0f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.slot-time {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
}

.time-separator {
  color: #999;
}

.slot-card-body {
  padding: 16px;
  display: flex;
  gap: 24px;
}

.quota-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.quota-label {
  font-size: 12px;
  color: #999;
}

.quota-value {
  font-size: 20px;
  font-weight: 600;
  color: #1a1a1a;
}

.slot-card-footer {
  padding: 12px 16px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  gap: 8px;
}

@media (max-width: 900px) {
  .manage-container {
    flex-direction: column;
    height: auto;
  }

  .resource-panel {
    width: 100%;
    border-right: none;
    border-bottom: 1px solid #e8e8e8;
    max-height: 400px;
  }

  .slot-panel {
    min-height: 500px;
  }
}

@media (max-width: 768px) {
  .page-header {
    padding: 16px;
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }

  .page-title {
    font-size: 24px;
  }

  .slot-header {
    padding: 16px;
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }

  .slot-content {
    padding: 16px;
  }

  .slot-grid {
    grid-template-columns: 1fr;
  }
}
</style>
