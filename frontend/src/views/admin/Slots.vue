<script setup>
import { ref, onMounted } from 'vue'
import {
  NCard,
  NSpace,
  NButton,
  NIcon,
  NTag,
  NSpin,
  NEmpty,
  NSelect,
  NPagination,
  NModal,
  NForm,
  NFormItem,
  NInputNumber,
  NDatePicker,
  useMessage,
  useDialog
} from 'naive-ui'
import {
  AddOutline,
  TrashOutline,
  CreateOutline,
  RefreshOutline,
  FlameOutline,
  TimeOutline
} from '@vicons/ionicons5'
import { fetchSlotPage, createSlot, updateSlot, deleteSlot } from '@/api/slot'
import { fetchResourceList } from '@/api/resource'

const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const slots = ref([])
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const filterResource = ref(null)
const filterSlotType = ref(null)
const filterStatus = ref(null)

const resources = ref([])

const showModal = ref(false)
const modalType = ref('add')
const formRef = ref(null)
const submitting = ref(false)
const formData = ref({
  id: null,
  resourceId: null,
  startDatetime: null,
  endDatetime: null,
  slotType: 'NORMAL',
  openTime: null,
  totalQuota: 10,
  status: 'AVAILABLE'
})

const slotTypeOptions = [
  { label: '全部类型', value: null },
  { label: '普通时段', value: 'NORMAL' },
  { label: '热门时段', value: 'HOT' }
]

const statusOptions = [
  { label: '全部状态', value: null },
  { label: '可用', value: 'AVAILABLE' },
  { label: '禁用', value: 'DISABLED' }
]

const slotTypeOptionsForm = [
  { label: '普通时段', value: 'NORMAL' },
  { label: '热门时段', value: 'HOT' }
]

const statusOptionsForm = [
  { label: '可用', value: 'AVAILABLE' },
  { label: '禁用', value: 'DISABLED' }
]

const rules = {
  resourceId: [{ required: true, type: 'number', message: '请选择资源', trigger: 'change' }],
  startDatetime: [{ required: true, type: 'number', message: '请选择开始时间', trigger: 'change' }],
  endDatetime: [{ required: true, type: 'number', message: '请选择结束时间', trigger: 'change' }],
  slotType: [{ required: true, message: '请选择时段类型', trigger: 'change' }],
  totalQuota: [{ required: true, type: 'number', message: '请输入总名额', trigger: 'blur' }],
  status: [{ required: true, message: '请选择时段状态', trigger: 'change' }]
}

const getSlotTypeColor = (type) => type === 'HOT' ? 'error' : 'default'
const getSlotTypeText = (type) => type === 'HOT' ? '热门时段' : '普通时段'
const getStatusColor = (status) => status === 'AVAILABLE' ? 'success' : 'default'
const getStatusText = (status) => status === 'AVAILABLE' ? '可用' : '禁用'

const formatDateTime = (timestamp) => {
  if (!timestamp) return '-'
  const date = new Date(timestamp)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}`
}

async function loadResources() {
  try {
    resources.value = await fetchResourceList()
  } catch (error) {
    message.error(error.message || '加载资源列表失败')
  }
}

async function loadSlots() {
  try {
    loading.value = true
    const params = { pageNum: currentPage.value, pageSize: pageSize.value }
    if (filterResource.value) params.resourceId = filterResource.value
    if (filterSlotType.value) params.slotType = filterSlotType.value
    if (filterStatus.value) params.status = filterStatus.value
    const data = await fetchSlotPage(params)
    slots.value = data.records
    total.value = data.total
  } catch (error) {
    message.error(error.message || '加载时段列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadSlots()
}

function handlePageChange(page) {
  currentPage.value = page
  loadSlots()
}

function handlePageSizeChange(size) {
  pageSize.value = size
  currentPage.value = 1
  loadSlots()
}

function openAddModal() {
  modalType.value = 'add'
  formData.value = {
    id: null,
    resourceId: null,
    startDatetime: null,
    endDatetime: null,
    slotType: 'NORMAL',
    openTime: null,
    totalQuota: 10,
    status: 'AVAILABLE'
  }
  showModal.value = true
}

function openEditModal(slot) {
  modalType.value = 'edit'
  formData.value = {
    ...slot,
    startDatetime: new Date(slot.startDatetime).getTime(),
    endDatetime: new Date(slot.endDatetime).getTime(),
    openTime: slot.openTime ? new Date(slot.openTime).getTime() : null
  }
  showModal.value = true
}

async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  if (formData.value.startDatetime >= formData.value.endDatetime) {
    message.error('结束时间必须大于开始时间')
    return
  }

  try {
    submitting.value = true
    const submitData = {
      ...formData.value,
      startDatetime: new Date(formData.value.startDatetime).toISOString(),
      endDatetime: new Date(formData.value.endDatetime).toISOString(),
      openTime: formData.value.openTime ? new Date(formData.value.openTime).toISOString() : null
    }

    if (modalType.value === 'add') {
      await createSlot(submitData)
      message.success('添加成功')
    } else {
      await updateSlot(submitData)
      message.success('更新成功')
    }
    showModal.value = false
    await loadSlots()
  } catch (error) {
    message.error(error.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

function handleDelete(slot) {
  dialog.warning({
    title: '确认删除',
    content: '确定要删除该时段吗？',
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteSlot(slot.id)
        message.success('删除成功')
        await loadSlots()
      } catch (error) {
        message.error(error.message || '删除失败')
      }
    }
  })
}

onMounted(() => {
  loadResources()
  loadSlots()
})
</script>

<template>
  <div class="admin-page">
    <div class="page-header">
      <h1 class="page-title">时段管理</h1>
      <p class="page-subtitle">管理资源预约时段</p>
    </div>

    <n-card class="filter-card" :bordered="false">
      <n-space :size="12" align="center" justify="space-between" wrap>
        <n-space :size="12">
          <n-select
            v-model:value="filterResource"
            :options="[{ label: '全部资源', value: null }, ...resources.map(r => ({ label: r.resourceName, value: r.id }))]"
            placeholder="选择资源"
            clearable
            style="width: 180px"
            @update:value="handleSearch"
          />
          <n-select
            v-model:value="filterSlotType"
            :options="slotTypeOptions"
            placeholder="时段类型"
            clearable
            style="width: 130px"
            @update:value="handleSearch"
          />
          <n-select
            v-model:value="filterStatus"
            :options="statusOptions"
            placeholder="时段状态"
            clearable
            style="width: 130px"
            @update:value="handleSearch"
          />
        </n-space>
        <n-space :size="12">
          <n-button type="primary" @click="openAddModal">
            <template #icon><n-icon :component="AddOutline" /></template>
            添加时段
          </n-button>
          <n-button @click="loadSlots">
            <template #icon><n-icon :component="RefreshOutline" /></template>
            刷新
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <div class="list-container">
      <n-spin :show="loading">
        <n-empty v-if="!loading && slots.length === 0" description="暂无时段" style="margin-top: 80px" />

        <div v-else class="slot-list">
          <n-card v-for="slot in slots" :key="slot.id" class="list-card" :bordered="false">
            <div class="card-content">
              <div class="card-left">
                <div class="time-info">
                  <n-icon :component="TimeOutline" :size="20" />
                  <div class="time-text">
                    <div class="time-range">{{ formatDateTime(slot.startDatetime) }} - {{ formatDateTime(slot.endDatetime) }}</div>
                    <div class="resource-name">{{ slot.resourceName || `资源ID: ${slot.resourceId}` }}</div>
                  </div>
                </div>
              </div>

              <div class="card-middle">
                <div class="info-block">
                  <div class="info-label">类型</div>
                  <n-tag :type="getSlotTypeColor(slot.slotType)" size="small" round>
                    <template v-if="slot.slotType === 'HOT'" #icon>
                      <n-icon :component="FlameOutline" />
                    </template>
                    {{ getSlotTypeText(slot.slotType) }}
                  </n-tag>
                </div>
                <div class="info-block">
                  <div class="info-label">状态</div>
                  <n-tag :type="getStatusColor(slot.status)" size="small" round>
                    {{ getStatusText(slot.status) }}
                  </n-tag>
                </div>
                <div class="info-block">
                  <div class="info-label">名额</div>
                  <div class="quota-info">
                    <span class="quota-number">{{ slot.remainQuota || 0 }}</span> / {{ slot.totalQuota }}
                  </div>
                </div>
              </div>

              <div class="card-right">
                <n-button size="small" @click="openEditModal(slot)">
                  <template #icon><n-icon :component="CreateOutline" /></template>
                  编辑
                </n-button>
                <n-button size="small" type="error" @click="handleDelete(slot)">
                  <template #icon><n-icon :component="TrashOutline" /></template>
                  删除
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

    <n-modal v-model:show="showModal" preset="card" :title="modalType === 'add' ? '添加时段' : '编辑时段'" style="width: 520px" :bordered="false" segmented>
      <n-form ref="formRef" :model="formData" :rules="rules" label-placement="left" label-width="80">
        <n-form-item label="所属资源" path="resourceId">
          <n-select v-model:value="formData.resourceId" :options="resources.map(r => ({ label: r.resourceName, value: r.id }))" placeholder="请选择资源" />
        </n-form-item>
        <n-form-item label="开始时间" path="startDatetime">
          <n-date-picker v-model:value="formData.startDatetime" type="datetime" placeholder="请选择开始时间" style="width: 100%" />
        </n-form-item>
        <n-form-item label="结束时间" path="endDatetime">
          <n-date-picker v-model:value="formData.endDatetime" type="datetime" placeholder="请选择结束时间" style="width: 100%" />
        </n-form-item>
        <n-form-item label="时段类型" path="slotType">
          <n-select v-model:value="formData.slotType" :options="slotTypeOptionsForm" placeholder="请选择时段类型" />
        </n-form-item>
        <n-form-item v-if="formData.slotType === 'HOT'" label="开放时间" path="openTime">
          <n-date-picker v-model:value="formData.openTime" type="datetime" placeholder="请选择开放预约时间" style="width: 100%" />
        </n-form-item>
        <n-form-item label="总名额" path="totalQuota">
          <n-input-number v-model:value="formData.totalQuota" :min="0" placeholder="请输入总名额" style="width: 100%" />
        </n-form-item>
        <n-form-item label="时段状态" path="status">
          <n-select v-model:value="formData.status" :options="statusOptionsForm" placeholder="请选择时段状态" />
        </n-form-item>
      </n-form>

      <template #footer>
        <n-space justify="end">
          <n-button @click="showModal = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="handleSubmit">
            {{ modalType === 'add' ? '添加' : '保存' }}
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

.slot-list {
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
  width: 320px;
  padding-right: 24px;
  border-right: 2px solid #f0f0f0;
}

.time-info {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.time-text {
  flex: 1;
}

.time-range {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 4px;
}

.resource-name {
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
  width: 120px;
}

.info-label {
  font-size: 12px;
  color: #999;
}

.quota-info {
  font-size: 14px;
  color: #666;
}

.quota-number {
  font-weight: 600;
  color: #18a058;
}

.card-right {
  display: flex;
  gap: 8px;
  padding-left: 24px;
  border-left: 2px solid #f0f0f0;
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

  .card-middle {
    width: 100%;
    order: 3;
    flex-wrap: wrap;
  }

  .card-right {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
