<script setup>
import { ref, onMounted, watch } from 'vue'
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
  NSwitch,
  useMessage,
  useDialog
} from 'naive-ui'
import {
  PersonOutline,
  SearchOutline,
  RefreshOutline,
  CallOutline,
  CalendarOutline,
  ShieldCheckmarkOutline,
  BanOutline,
  LockClosedOutline,
  CheckmarkCircleOutline,
  ChevronDownOutline,
  ChevronUpOutline,
  BookOutline,
  TimeOutline,
  TrendingUpOutline,
  FlameOutline
} from '@vicons/ionicons5'
import { fetchUserPage, updateUserStatus, resetUserPassword, fetchUserOverview, fetchUserReservations } from '@/api/user'
import { useDictStore } from '@/store/dict'

const message = useMessage()
const dialog = useDialog()
const dictStore = useDictStore()

// 加载状态
const loading = ref(false)

// 用户列表
const users = ref([])

// 展开的用户ID
const expandedUserId = ref(null)

// 用户概览数据
const userOverview = ref(null)
const overviewLoading = ref(false)

// 字典数据
const resourceTypeDict = ref([])
const slotTypeDict = ref([])

// 分页信息
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

// 搜索和筛选
const searchKeyword = ref('')
const filterRole = ref(null)
const filterStatus = ref(null)

// 角色选项
const roleOptions = [
  { label: '全部角色', value: null },
  { label: '管理员', value: 'ADMIN' },
  { label: '普通用户', value: 'USER' }
]

// 状态选项
const statusOptions = [
  { label: '全部状态', value: null },
  { label: '激活', value: 'ACTIVE' },
  { label: '禁用', value: 'DISABLED' }
]

// 格式化时间
const formatDateTime = (datetime) => {
  if (!datetime) return '-'
  const date = new Date(datetime)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

// 加载用户列表
async function loadUsers() {
  try {
    loading.value = true
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value
    }

    if (searchKeyword.value) params.keyword = searchKeyword.value
    if (filterRole.value) params.role = filterRole.value
    if (filterStatus.value) params.status = filterStatus.value

    const data = await fetchUserPage(params)
    users.value = data.records
    total.value = data.total
  } catch (error) {
    message.error(error.message || '加载用户列表失败')
  } finally {
    loading.value = false
  }
}

// 切换用户状态
async function handleToggleStatus(user) {
  const newStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const actionText = newStatus === 'ACTIVE' ? '启用' : '禁用'

  dialog.warning({
    title: '确认操作',
    content: `确定要${actionText}用户 "${user.nickname}" 吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await updateUserStatus(user.id, { status: newStatus })
        message.success(`${actionText}成功`)
        await loadUsers()
      } catch (error) {
        message.error(error.message || `${actionText}失败`)
      }
    }
  })
}

// 重置密码
async function handleResetPassword(user) {
  dialog.warning({
    title: '确认重置密码',
    content: `确定要重置用户 "${user.nickname}" 的密码吗？新密码将为：123456`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await resetUserPassword(user.id)
        message.success('密码已重置为：123456')
      } catch (error) {
        message.error(error.message || '重置密码失败')
      }
    }
  })
}

// 页码改变
function handlePageChange(page) {
  currentPage.value = page
  loadUsers()
}

// 每页条数改变
function handlePageSizeChange(size) {
  pageSize.value = size
  currentPage.value = 1
  loadUsers()
}

// 搜索
function handleSearch() {
  currentPage.value = 1
  loadUsers()
}

// 切换展开/收起
async function toggleUserExpand(user) {
  if (expandedUserId.value === user.id) {
    expandedUserId.value = null
    userOverview.value = null
  } else {
    expandedUserId.value = user.id
    await loadUserOverview(user.id)
  }
}

// 加载用户概览
async function loadUserOverview(userId) {
  try {
    overviewLoading.value = true
    userOverview.value = await fetchUserOverview(userId)
  } catch (error) {
    message.error(error.message || '加载用户概览失败')
  } finally {
    overviewLoading.value = false
  }
}

// 加载字典数据
async function loadDictData() {
  try {
    resourceTypeDict.value = await dictStore.getDictData('resource_type')
    slotTypeDict.value = await dictStore.getDictData('slot_type')
  } catch (error) {
    console.error('加载字典数据失败', error)
  }
}

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

// 初始化
onMounted(() => {
  loadDictData()
  loadUsers()
})
</script>

<template>
  <div class="users-page">
    <!-- 页面标题 -->
    <div class="page-header">
      <h1 class="page-title">用户管理</h1>
      <p class="page-subtitle">管理系统用户，包括启用/禁用、重置密码等操作</p>
    </div>

    <!-- 搜索和筛选栏 -->
    <n-card class="filter-card" :bordered="false">
      <n-space :size="16" align="center" justify="space-between" wrap>
        <!-- 搜索框 -->
        <n-input
          v-model:value="searchKeyword"
          placeholder="搜索用户名、昵称或手机号..."
          clearable
          style="width: 320px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

        <!-- 筛选器 -->
        <n-space :size="12">
          <n-select
            v-model:value="filterRole"
            :options="roleOptions"
            placeholder="用户角色"
            clearable
            style="width: 140px"
            @update:value="handleSearch"
          />
          <n-select
            v-model:value="filterStatus"
            :options="statusOptions"
            placeholder="用户状态"
            clearable
            style="width: 140px"
            @update:value="handleSearch"
          />
          <n-button type="primary" @click="handleSearch">
            <template #icon>
              <n-icon :component="RefreshOutline" />
            </template>
            刷新
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <!-- 用户列表 -->
    <div class="users-container">
      <n-spin :show="loading">
        <!-- 空状态 -->
        <n-empty
          v-if="!loading && users.length === 0"
          description="暂无用户"
          style="margin-top: 120px"
        />

        <!-- 用户列表 -->
        <div v-else class="user-list">
          <n-card
            v-for="user in users"
            :key="user.id"
            class="user-card"
            :bordered="false"
          >
            <div class="user-content">
              <!-- 左侧：头像和基本信息 -->
              <div class="user-left">
                <div class="user-avatar">
                  <n-icon :component="PersonOutline" :size="24" />
                </div>
                <div class="user-info">
                  <h3 class="user-nickname">{{ user.nickname }}</h3>
                  <div class="user-username">@{{ user.username }}</div>
                </div>
              </div>

              <!-- 中间：详细信息 -->
              <div class="user-middle">
                <div class="info-item">
                  <n-icon :component="CallOutline" :size="16" />
                  <span class="info-text">{{ user.phone || '未设置' }}</span>
                </div>
                <div class="info-item">
                  <n-icon :component="CalendarOutline" :size="16" />
                  <span class="info-text">{{ formatDateTime(user.createdAt) }}</span>
                </div>
                <div class="info-item">
                  <n-icon :component="ShieldCheckmarkOutline" :size="16" />
                  <n-tag
                    :type="user.role === 'ADMIN' ? 'error' : 'info'"
                    size="small"
                    round
                  >
                    {{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}
                  </n-tag>
                </div>
              </div>

              <!-- 右侧：状态和操作 -->
              <div class="user-right">
                <div class="status-wrapper">
                  <div class="status-label">状态</div>
                  <n-switch
                    :value="user.status === 'ACTIVE'"
                    @update:value="handleToggleStatus(user)"
                  >
                    <template #checked>激活</template>
                    <template #unchecked>禁用</template>
                  </n-switch>
                </div>
                <div class="action-wrapper">
                  <n-button
                    size="small"
                    @click="toggleUserExpand(user)"
                  >
                    <template #icon>
                      <n-icon :component="expandedUserId === user.id ? ChevronUpOutline : ChevronDownOutline" />
                    </template>
                    {{ expandedUserId === user.id ? '收起' : '详情' }}
                  </n-button>
                  <n-button
                    size="small"
                    type="primary"
                    @click="handleResetPassword(user)"
                  >
                    <template #icon>
                      <n-icon :component="LockClosedOutline" />
                    </template>
                    重置密码
                  </n-button>
                </div>
              </div>
            </div>

            <!-- 展开的详情面板 -->
            <div v-if="expandedUserId === user.id" class="user-detail-panel">
              <n-spin :show="overviewLoading">
                <div v-if="userOverview" class="overview-content">
                  <!-- 统计卡片 -->
                  <div class="stats-grid">
                    <div class="stat-card">
                      <div class="stat-icon" style="background: #667eea15;">
                        <n-icon :component="BookOutline" :size="20" style="color: #667eea;" />
                      </div>
                      <div class="stat-info">
                        <div class="stat-value">{{ userOverview.totalReservationCount || 0 }}</div>
                        <div class="stat-label">总预约次数</div>
                      </div>
                    </div>
                    <div class="stat-card">
                      <div class="stat-icon" style="background: #18a05815;">
                        <n-icon :component="TimeOutline" :size="20" style="color: #18a058;" />
                      </div>
                      <div class="stat-info">
                        <div class="stat-value">{{ userOverview.activeReservationCount || 0 }}</div>
                        <div class="stat-label">进行中</div>
                      </div>
                    </div>
                    <div class="stat-card">
                      <div class="stat-icon" style="background: #2080f015;">
                        <n-icon :component="TrendingUpOutline" :size="20" style="color: #2080f0;" />
                      </div>
                      <div class="stat-info">
                        <div class="stat-value">{{ userOverview.recent30DayReservationCount || 0 }}</div>
                        <div class="stat-label">近30天</div>
                      </div>
                    </div>
                    <div class="stat-card">
                      <div class="stat-icon" style="background: #f0a02015;">
                        <n-icon :component="FlameOutline" :size="20" style="color: #f0a020;" />
                      </div>
                      <div class="stat-info">
                        <div class="stat-value">{{ userOverview.finishedReservationCount || 0 }}</div>
                        <div class="stat-label">已完成</div>
                      </div>
                    </div>
                  </div>

                  <!-- 偏好信息 -->
                  <div class="preference-section">
                    <h4 class="section-title">用户偏好</h4>
                    <div class="preference-grid">
                      <div class="preference-item">
                        <span class="preference-label">常用资源</span>
                        <span class="preference-value">{{ userOverview.favoriteResourceName || '暂无' }}</span>
                      </div>
                      <div class="preference-item">
                        <span class="preference-label">偏好类型</span>
                        <span class="preference-value">{{ getResourceTypeName(userOverview.favoriteResourceType) }}</span>
                      </div>
                      <div class="preference-item">
                        <span class="preference-label">偏好时段</span>
                        <span class="preference-value">{{ getSlotTypeName(userOverview.favoriteSourceType) }}</span>
                      </div>
                      <div class="preference-item">
                        <span class="preference-label">常用时间</span>
                        <span class="preference-value">{{ userOverview.favoriteTimeBucket || '暂无' }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </n-spin>
            </div>
          </n-card>
        </div>

        <!-- 分页 -->
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
            <template #prefix="{ itemCount }">
              共 {{ itemCount }} 条
            </template>
          </n-pagination>
        </div>
      </n-spin>
    </div>
  </div>
</template>

<style scoped>
.users-page {
  min-height: 100%;
  background: #fff;
  padding: 24px;
}

/* 页面头部 */
.page-header {
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
  font-weight: 400;
}

/* 筛选卡片 */
.filter-card {
  margin-bottom: 24px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  border: 1px solid #f0f0f0;
}

/* 用户容器 */
.users-container {
  min-height: 400px;
}

/* 用户列表 */
.user-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 用户卡片 */
.user-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  transition: all 0.3s ease;
  border: 1px solid #f0f0f0;
}

.user-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  transform: translateY(-2px);
}

.user-card :deep(.n-card__content) {
  padding: 20px 24px;
}

/* 用户内容布局 */
.user-content {
  display: flex;
  align-items: center;
  gap: 0;
}

/* 左侧：头像和基本信息 */
.user-left {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 220px;
  padding-right: 24px;
  border-right: 2px solid #f0f0f0;
}

.user-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}

.user-info {
  flex: 1;
  min-width: 0;
}

.user-nickname {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 4px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-username {
  font-size: 13px;
  color: #999;
  font-family: 'SF Mono', 'Monaco', 'Courier New', monospace;
}

/* 中间：详细信息 */
.user-middle {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 0;
  padding: 0 24px;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #666;
  width: 180px;
}

.info-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 右侧：状态和操作 */
.user-right {
  display: flex;
  align-items: center;
  gap: 16px;
  padding-left: 24px;
  border-left: 2px solid #f0f0f0;
}

.status-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  min-width: 80px;
}

.status-label {
  font-size: 12px;
  color: #999;
}

.action-wrapper {
  display: flex;
  gap: 8px;
}

/* 分页 */
.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid #f0f0f0;
}

/* 响应式 */
@media (max-width: 1200px) {
  .user-content {
    flex-wrap: wrap;
    gap: 20px;
  }

  .user-middle {
    width: 100%;
    order: 3;
  }
}

@media (max-width: 768px) {
  .users-page {
    padding: 16px;
  }

  .page-title {
    font-size: 28px;
  }

  .filter-card :deep(.n-input) {
    width: 100% !important;
  }

  .user-left {
    min-width: auto;
    flex: 1;
  }

  .user-middle {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .user-right {
    flex-direction: column;
    align-items: flex-end;
    gap: 12px;
  }

  .user-card :deep(.n-card__content) {
    padding: 16px;
  }
}

/* 用户详情面板 */
.user-detail-panel {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 2px solid #f0f0f0;
}

.overview-content {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* 统计卡片网格 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: #fafafa;
  border-radius: 12px;
  border: 1px solid #f0f0f0;
  transition: all 0.2s ease;
}

.stat-card:hover {
  background: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
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
  background: #fafafa;
  border-radius: 12px;
  padding: 20px;
  border: 1px solid #f0f0f0;
}

.section-title {
  font-size: 15px;
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
  font-size: 14px;
  font-weight: 600;
  color: #1a1a1a;
}
</style>
