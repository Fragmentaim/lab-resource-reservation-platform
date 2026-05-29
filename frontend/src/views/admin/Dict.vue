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
  NInput,
  NSelect,
  NPagination,
  NModal,
  NForm,
  NFormItem,
  NSwitch,
  useMessage,
  useDialog
} from 'naive-ui'
import {
  AddOutline,
  TrashOutline,
  CreateOutline,
  RefreshOutline,
  BookOutline
} from '@vicons/ionicons5'
import {
  fetchDictTypeList,
  fetchDictPage,
  fetchDictById,
  createDict,
  updateDict,
  deleteDict
} from '@/api/dict'

const message = useMessage()
const dialog = useDialog()

// 字典类型列表
const dictTypes = ref([])
const selectedDictType = ref(null)

// 字典数据列表
const dictData = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

// 拖拽相关
const draggedIndex = ref(null)
const dragOverIndex = ref(null)

// 弹窗
const showModal = ref(false)
const modalType = ref('add')
const formRef = ref(null)
const submitting = ref(false)
const formData = ref({
  id: null,
  dictType: '',
  dictLabel: '',
  dictValue: '',
  sortOrder: 0,
  isDefault: 'N'
})

const rules = {
  dictType: [{ required: true, message: '请选择字典类型', trigger: 'change' }],
  dictLabel: [{ required: true, message: '请输入字典标签', trigger: 'blur' }],
  dictValue: [{ required: true, message: '请输入字典值', trigger: 'blur' }],
  sortOrder: [{ required: true, type: 'number', message: '请输入排序', trigger: 'blur' }]
}

// 加载字典类型列表
async function loadDictTypes() {
  try {
    dictTypes.value = await fetchDictTypeList()
    if (dictTypes.value.length > 0 && !selectedDictType.value) {
      selectedDictType.value = dictTypes.value[0].dictType
      await loadDictData()
    }
  } catch (error) {
    message.error(error.message || '加载字典类型失败')
  }
}

// 加载字典数据
async function loadDictData() {
  if (!selectedDictType.value) return

  try {
    loading.value = true
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      type: selectedDictType.value
    }
    const data = await fetchDictPage(params)
    dictData.value = data.records
    total.value = data.total
  } catch (error) {
    message.error(error.message || '加载字典数据失败')
  } finally {
    loading.value = false
  }
}

// 切换字典类型
function handleDictTypeChange() {
  currentPage.value = 1
  loadDictData()
}

// 分页
function handlePageChange(page) {
  currentPage.value = page
  loadDictData()
}

function handlePageSizeChange(size) {
  pageSize.value = size
  currentPage.value = 1
  loadDictData()
}

// 打开添加弹窗
function openAddModal() {
  modalType.value = 'add'
  formData.value = {
    id: null,
    dictType: selectedDictType.value,
    dictLabel: '',
    dictValue: '',
    sortOrder: 0,
    isDefault: 'N'
  }
  showModal.value = true
}

// 打开编辑弹窗
async function openEditModal(item) {
  modalType.value = 'edit'
  try {
    const detail = await fetchDictById(item.id)
    formData.value = { ...detail }
    showModal.value = true
  } catch (error) {
    message.error(error.message || '加载字典详情失败')
  }
}

// 提交表单
async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  try {
    submitting.value = true
    if (modalType.value === 'add') {
      await createDict(formData.value)
      message.success('添加成功')
    } else {
      await updateDict(formData.value)
      message.success('更新成功')
    }
    showModal.value = false
    await loadDictData()
  } catch (error) {
    message.error(error.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

// 删除字典
function handleDelete(item) {
  dialog.warning({
    title: '确认删除',
    content: `确定要删除字典项 "${item.dictLabel}" 吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteDict(item.id)
        message.success('删除成功')
        await loadDictData()
      } catch (error) {
        message.error(error.message || '删除失败')
      }
    }
  })
}

// 切换默认状态
async function handleToggleDefault(item) {
  const newDefault = item.isDefault === 'Y' ? 'N' : 'Y'
  try {
    await updateDict({
      ...item,
      isDefault: newDefault
    })
    message.success(newDefault === 'Y' ? '已设为默认' : '已取消默认')
    await loadDictData()
  } catch (error) {
    message.error(error.message || '操作失败')
  }
}

// 拖拽开始
function handleDragStart(index) {
  draggedIndex.value = index
}

// 拖拽经过
function handleDragOver(e, index) {
  e.preventDefault()
  dragOverIndex.value = index
}

// 拖拽离开
function handleDragLeave() {
  dragOverIndex.value = null
}

// 拖拽放下
async function handleDrop(targetIndex) {
  if (draggedIndex.value === null || draggedIndex.value === targetIndex) {
    draggedIndex.value = null
    dragOverIndex.value = null
    return
  }

  const items = [...dictData.value]
  const draggedItem = items[draggedIndex.value]
  items.splice(draggedIndex.value, 1)
  items.splice(targetIndex, 0, draggedItem)

  // 立即更新视图，重新计算排序值
  const updatedItems = items.map((item, index) => ({
    ...item,
    sortOrder: index
  }))

  dictData.value = updatedItems

  // 批量更新到后端
  try {
    for (const update of updatedItems) {
      await updateDict(update)
    }
    message.success('排序已更新')
  } catch (error) {
    message.error(error.message || '更新排序失败')
    await loadDictData()
  } finally {
    draggedIndex.value = null
    dragOverIndex.value = null
  }
}

// 拖拽结束
function handleDragEnd() {
  draggedIndex.value = null
  dragOverIndex.value = null
}

onMounted(() => {
  loadDictTypes()
})
</script>

<template>
  <div class="dict-page">
    <div class="page-header">
      <h1 class="page-title">字典管理</h1>
      <p class="page-subtitle">管理系统字典数据</p>
    </div>

    <n-card class="filter-card" :bordered="false">
      <n-space :size="12" align="center" justify="space-between" wrap>
        <n-space :size="12">
          <n-select
            v-model:value="selectedDictType"
            :options="dictTypes.map(t => ({ label: t.dictName, value: t.dictType }))"
            placeholder="选择字典类型"
            style="width: 200px"
            @update:value="handleDictTypeChange"
          />
        </n-space>
        <n-space :size="12">
          <n-button type="primary" @click="openAddModal">
            <template #icon><n-icon :component="AddOutline" /></template>
            添加字典
          </n-button>
          <n-button @click="loadDictData">
            <template #icon><n-icon :component="RefreshOutline" /></template>
            刷新
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <div class="dict-container">
      <n-spin :show="loading">
        <n-empty v-if="!loading && dictData.length === 0" description="暂无字典数据" style="margin-top: 80px" />

        <div v-else class="dict-list">
          <n-card
            v-for="(item, index) in dictData"
            :key="item.id"
            class="dict-card"
            :class="{
              'dragging': draggedIndex === index,
              'drag-over-below': dragOverIndex === index && draggedIndex !== index && draggedIndex < index,
              'drag-over-above': dragOverIndex === index && draggedIndex !== index && draggedIndex > index
            }"
            :bordered="false"
            draggable="true"
            @dragstart="handleDragStart(index)"
            @dragover="handleDragOver($event, index)"
            @dragleave="handleDragLeave"
            @drop="handleDrop(index)"
            @dragend="handleDragEnd"
          >
            <div class="dict-content">
              <div class="dict-left">
                <div class="dict-icon">
                  <n-icon :component="BookOutline" :size="20" />
                </div>
                <div class="dict-info">
                  <div class="dict-label">{{ item.dictLabel }}</div>
                  <div class="dict-value">{{ item.dictValue }}</div>
                </div>
              </div>

              <div class="dict-middle">
                <div class="info-item">
                  <span class="info-label">排序</span>
                  <span class="info-value">{{ item.sortOrder }}</span>
                </div>
                <div class="info-item">
                  <span class="info-label">默认</span>
                  <n-switch
                    :value="item.isDefault === 'Y'"
                    size="small"
                    @update:value="handleToggleDefault(item)"
                  />
                </div>
              </div>

              <div class="dict-right">
                <n-button size="small" @click="openEditModal(item)">
                  <template #icon><n-icon :component="CreateOutline" /></template>
                  编辑
                </n-button>
                <n-button size="small" type="error" @click="handleDelete(item)">
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

    <!-- 添加/编辑弹窗 -->
    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="modalType === 'add' ? '添加字典' : '编辑字典'"
      style="width: 520px"
      :bordered="false"
      segmented
    >
      <n-form
        ref="formRef"
        :model="formData"
        :rules="rules"
        label-placement="left"
        label-width="80"
      >
        <n-form-item label="字典类型" path="dictType">
          <n-select
            v-model:value="formData.dictType"
            :options="dictTypes.map(t => ({ label: t.dictName, value: t.dictType }))"
            placeholder="请选择字典类型"
          />
        </n-form-item>
        <n-form-item label="字典标签" path="dictLabel">
          <n-input v-model:value="formData.dictLabel" placeholder="请输入字典标签（显示文本）" />
        </n-form-item>
        <n-form-item label="字典值" path="dictValue">
          <n-input v-model:value="formData.dictValue" placeholder="请输入字典值（英文标识）" />
        </n-form-item>
        <n-form-item label="是否默认" path="isDefault">
          <n-select
            v-model:value="formData.isDefault"
            :options="[
              { label: '是', value: 'Y' },
              { label: '否', value: 'N' }
            ]"
            placeholder="请选择是否默认"
          />
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
.dict-page {
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

.dict-container {
  min-height: 400px;
}

.dict-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: relative;
}

.dict-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  transition: all 0.3s ease;
  border: 1px solid #f0f0f0;
  cursor: move;
  position: relative;
}

.dict-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  transform: translateY(-2px);
}

.dict-card.dragging {
  opacity: 0.5;
  transform: scale(0.98);
}

.dict-card.drag-over-below::after {
  content: '';
  position: absolute;
  bottom: -7px;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
  border-radius: 2px;
  box-shadow: 0 0 8px rgba(102, 126, 234, 0.5);
  animation: pulse 1s ease-in-out infinite;
}

.dict-card.drag-over-above::before {
  content: '';
  position: absolute;
  top: -7px;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
  border-radius: 2px;
  box-shadow: 0 0 8px rgba(102, 126, 234, 0.5);
  animation: pulse 1s ease-in-out infinite;
}

/* 插入线指示器 */
.drop-indicator {
  position: absolute;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
  border-radius: 2px;
  box-shadow: 0 0 8px rgba(102, 126, 234, 0.5);
  animation: pulse 1s ease-in-out infinite;
  pointer-events: none;
  z-index: 10;
}

@keyframes pulse {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0.6;
  }
}

.dict-card :deep(.n-card__content) {
  padding: 16px 20px;
}

.dict-content {
  display: flex;
  align-items: center;
  gap: 0;
}

.dict-left {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 280px;
  padding-right: 24px;
  border-right: 2px solid #f0f0f0;
}

.dict-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: #667eea15;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #667eea;
  flex-shrink: 0;
}

.dict-info {
  flex: 1;
  min-width: 0;
}

.dict-label {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dict-value {
  font-size: 13px;
  color: #999;
  font-family: 'Courier New', monospace;
}

.dict-middle {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 0;
  padding: 0 24px;
}

.info-item {
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

.info-value {
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
}

.dict-right {
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
  .dict-page {
    padding: 16px;
  }

  .page-title {
    font-size: 28px;
  }

  .dict-content {
    flex-wrap: wrap;
    gap: 16px;
  }

  .dict-left {
    width: 100%;
    border-right: none;
  }

  .dict-middle {
    width: 100%;
    order: 3;
  }

  .dict-right {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
