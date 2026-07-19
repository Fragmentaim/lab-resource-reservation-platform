<script setup>
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  NButton, NCard, NDataTable, NEmpty, NForm, NFormItem, NIcon, NInput, NModal,
  NSelect, NSpace, NTag, NUpload, useMessage
} from 'naive-ui'
import {
  CloudUploadOutline, DocumentTextOutline, RefreshOutline, SearchOutline,
  TrashOutline
} from '@vicons/ionicons5'
import { useUserStore } from '@/store/user'
import {
  deleteKnowledgeDocument, fetchKnowledgeDocuments, reprocessKnowledgeDocument,
  uploadKnowledgeDocument
} from '@/api/knowledge'
import { fetchDocumentProcessEvents } from '@/api/documentAudit'

const message = useMessage()
const userStore = useUserStore()
const loading = ref(false)
const documents = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const status = ref(null)
const showUpload = ref(false)
const submitting = ref(false)
const uploadFiles = ref([])
const uploadForm = ref({ title: '', category: '', tags: '', visibility: 'PUBLIC', allowedUserIdsText: '' })
const showProcess = ref(false)
const processEventsLoading = ref(false)
const selectedDocument = ref(null)
const processEvents = ref([])
let refreshTimer

const statusOptions = [
  { label: '全部状态', value: null },
  { label: '等待处理', value: 'PENDING' },
  { label: '处理中', value: 'PROCESSING' },
  { label: '可问答', value: 'READY' },
  { label: '处理失败', value: 'FAILED' }
]

const visibilityOptions = [
  { label: '公开：所有登录用户可检索', value: 'PUBLIC' },
  { label: '仅管理员：仅管理员可检索', value: 'ADMIN_ONLY' },
  { label: '仅上传者：仅上传者和管理员可检索', value: 'UPLOADER_ONLY' },
  { label: '指定用户：仅授权用户和管理员可检索', value: 'SPECIFIED_USERS' }
]

const hasProcessingDocument = computed(() =>
  documents.value.some(item => ['PENDING', 'PROCESSING'].includes(item.status))
)

function statusLabel(value) {
  return ({ PENDING: '等待处理', PROCESSING: '处理中', READY: '可问答', FAILED: '处理失败' })[value] || value
}

function statusType(value) {
  return ({ PENDING: 'warning', PROCESSING: 'info', READY: 'success', FAILED: 'error' })[value] || 'default'
}

function visibilityLabel(value) {
  return ({
    PUBLIC: '公开', ADMIN_ONLY: '管理员', UPLOADER_ONLY: '上传者', SPECIFIED_USERS: '指定用户'
  })[value] || '公开'
}

function formatDate(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function formatSize(value) {
  if (!value) return '-'
  return value < 1024 * 1024 ? `${Math.ceil(value / 1024)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`
}

function parseQuality(row) {
  if (!row?.parseQuality) return null
  try {
    return typeof row.parseQuality === 'string' ? JSON.parse(row.parseQuality) : row.parseQuality
  } catch (_) {
    return null
  }
}

function qualityRouteSummary(row) {
  const decisions = parseQuality(row)?.decisions || []
  const counts = decisions.reduce((result, decision) => {
    const route = decision.route || decision.label
    if (route) result[route] = (result[route] || 0) + 1
    return result
  }, {})
  return Object.entries(counts).map(([route, count]) => `${route} ${count}`).join(' · ')
}

function qualityLabel(row) {
  if (row.status === 'FAILED') return '解析失败'
  if (!row.parserProvider) return row.status === 'READY' ? '未记录' : '等待解析'
  return qualityRouteSummary(row) || row.parserProvider
}

function qualityType(row) {
  const summary = qualityRouteSummary(row)
  if (summary.includes('REVIEW') || row.status === 'FAILED') return 'error'
  if (summary.includes('OCR') || summary.includes('HYBRID')) return 'warning'
  return row.status === 'READY' ? 'success' : 'default'
}

async function loadDocuments() {
  try {
    loading.value = true
    const result = await fetchKnowledgeDocuments({
      pageNum: page.value,
      pageSize: pageSize.value,
      keyword: keyword.value || undefined,
      status: status.value || undefined
    })
    documents.value = result.records || []
    total.value = Number(result.total || 0)
  } catch (error) {
    message.error(error.message || '加载知识库失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadDocuments()
}

function openUpload() {
  uploadFiles.value = []
  uploadForm.value = { title: '', category: '', tags: '', visibility: 'PUBLIC', allowedUserIdsText: '' }
  showUpload.value = true
}

async function submitUpload() {
  const file = uploadFiles.value[0]?.file
  if (!file) {
    message.warning('先选择一个 PDF、Word 或 Markdown 文件')
    return
  }
  try {
    submitting.value = true
    const allowedUserIds = uploadForm.value.allowedUserIdsText.split(',')
      .map(value => Number(value.trim()))
      .filter(value => Number.isInteger(value) && value > 0)
    if (uploadForm.value.visibility === 'SPECIFIED_USERS' && !allowedUserIds.length) {
      message.warning('指定用户范围至少填写一名用户 ID')
      return
    }
    await uploadKnowledgeDocument({ file, ...uploadForm.value, allowedUserIds })
    message.success('文档已入队，处理完成后即可参与问答')
    showUpload.value = false
    await loadDocuments()
  } catch (error) {
    message.error(error.message || '上传失败')
  } finally {
    submitting.value = false
  }
}

async function reprocess(row) {
  try {
    await reprocessKnowledgeDocument(row.id)
    message.success('已重新入队')
    await loadDocuments()
  } catch (error) {
    message.error(error.message || '重新处理失败')
  }
}

async function remove(row) {
  try {
    await deleteKnowledgeDocument(row.id)
    message.success('文档已删除')
    await loadDocuments()
  } catch (error) {
    message.error(error.message || '删除失败')
  }
}

async function openProcess(row) {
  selectedDocument.value = row
  processEvents.value = []
  showProcess.value = true
  try {
    processEventsLoading.value = true
    processEvents.value = await fetchDocumentProcessEvents(row.id) || []
  } catch (error) {
    message.error(error.message || '加载处理审计失败')
  } finally {
    processEventsLoading.value = false
  }
}

function stageLabel(stage) {
  return ({
    QUEUED: '进入队列', CLAIMED: 'Worker 已领取', VECTOR_CLEANUP: '清理旧索引',
    PARSING: '解析与向量化', PERSISTING_CHUNKS: '写入分块', COMPLETED: '处理完成',
    RETRY_PENDING: '等待重试', FAILED: '处理失败'
  })[stage] || stage
}

function eventType(status) {
  return ({ PENDING: 'warning', RUNNING: 'info', SUCCEEDED: 'success', FAILED: 'error' })[status] || 'default'
}

const columns = computed(() => [
  {
    title: '文档', key: 'title', minWidth: 260,
    render: row => h('div', { class: 'doc-cell' }, [
      h(NIcon, { size: 22, color: '#2d6a7a' }, { default: () => h(DocumentTextOutline) }),
      h('div', null, [h('div', { class: 'doc-title' }, row.title), h('div', { class: 'doc-meta' }, row.fileName)])
    ])
  },
  { title: '状态', key: 'status', width: 120, render: row => h(NTag, { type: statusType(row.status), round: true, size: 'small' }, { default: () => statusLabel(row.status) }) },
  { title: '可见范围', key: 'visibility', width: 110, render: row => h(NTag, { bordered: false, size: 'small' }, { default: () => visibilityLabel(row.visibility) }) },
  { title: '分块', key: 'chunkCount', width: 90, render: row => row.chunkCount ?? 0 },
  { title: '解析质量', key: 'parseQuality', width: 155, render: row => h(NTag, { type: qualityType(row), bordered: false, size: 'small' }, { default: () => qualityLabel(row) }) },
  { title: '分类 / 标签', key: 'category', minWidth: 160, render: row => h('div', { class: 'doc-meta' }, [row.category || '未分类', row.tags ? ` · ${row.tags}` : '']) },
  { title: '上传时间', key: 'createdAt', width: 180, render: row => formatDate(row.createdAt) },
  {
    title: '操作', key: 'actions', width: 250,
    render: row => userStore.isAdmin ? h(NSpace, { size: 4 }, {
      default: () => [
        h(NButton, { size: 'small', tertiary: true, onClick: () => openProcess(row) }, { default: () => '处理详情' }),
        h(NButton, { size: 'small', secondary: true, onClick: () => reprocess(row) }, { default: () => '重处理' }),
        h(NButton, { size: 'small', tertiary: true, type: 'error', onClick: () => remove(row) }, { default: () => '删除' })
      ]
    }) : '-'
  }
])

onMounted(async () => {
  await loadDocuments()
  refreshTimer = window.setInterval(() => {
    if (hasProcessingDocument.value) loadDocuments()
  }, 3000)
})

onBeforeUnmount(() => window.clearInterval(refreshTimer))
</script>

<template>
  <main class="knowledge-page">
    <section class="knowledge-hero">
      <div>
        <p class="eyebrow">LAB KNOWLEDGE / 证据库</p>
        <h1>让实验室资料<br><em>可以被追问。</em></h1>
        <p class="hero-copy">上传规程、记录与说明文档；系统会解析成可检索证据，并在问答时返回来源。</p>
      </div>
      <div class="hero-stat">
        <span>READY</span>
        <strong>{{ documents.filter(item => item.status === 'READY').length }}</strong>
        <small>当前页可问答文档</small>
      </div>
    </section>

    <n-card class="workspace-card" :bordered="false">
      <n-space justify="space-between" align="center" wrap>
        <n-space wrap>
          <n-input v-model:value="keyword" clearable placeholder="搜索标题或文件名" style="width: 240px" @keyup.enter="handleSearch">
            <template #prefix><n-icon :component="SearchOutline" /></template>
          </n-input>
          <n-select v-model:value="status" :options="statusOptions" clearable style="width: 140px" @update:value="handleSearch" />
          <n-button @click="handleSearch">筛选</n-button>
        </n-space>
        <n-button v-if="userStore.isAdmin" type="primary" @click="openUpload">
          <template #icon><n-icon :component="CloudUploadOutline" /></template>
          上传文档
        </n-button>
      </n-space>

      <n-data-table v-if="documents.length || loading" class="document-table" :columns="columns" :data="documents" :loading="loading" :row-key="row => row.id" :pagination="{
        page, pageSize, itemCount: total, showSizePicker: true, pageSizes: [10, 20, 50],
        onChange: value => { page = value; loadDocuments() },
        onUpdatePageSize: value => { pageSize = value; page = 1; loadDocuments() }
      }" />
      <n-empty v-else description="还没有知识库文档。上传第一份资料后，就能在智能问答里引用它。" class="empty-docs" />
    </n-card>

    <n-modal v-model:show="showProcess" preset="card" :title="`${selectedDocument?.title || '文档'} · 处理详情`" style="width: min(760px, calc(100vw - 32px))" :bordered="false">
      <template v-if="selectedDocument">
        <div class="quality-overview">
          <div><small>解析器</small><strong>{{ selectedDocument.parserProvider || '尚未完成' }}</strong></div>
          <div><small>版本</small><strong>{{ selectedDocument.parserVersion || '-' }}</strong></div>
          <div><small>处理版本</small><strong>{{ selectedDocument.docVersion || 'v1' }}</strong></div>
          <div><small>分块数</small><strong>{{ selectedDocument.chunkCount ?? 0 }}</strong></div>
        </div>
        <p v-if="qualityRouteSummary(selectedDocument)" class="quality-summary">页面路由：{{ qualityRouteSummary(selectedDocument) }}</p>
        <p v-if="selectedDocument.errorMessage" class="process-error">{{ selectedDocument.errorMessage }}</p>
      </template>

      <div v-if="processEventsLoading" class="process-empty">正在加载审计记录…</div>
      <div v-else-if="processEvents.length" class="process-timeline">
        <article v-for="(event, index) in processEvents" :key="`${event.traceId}-${index}-${event.createdAt}`" class="process-event">
          <span class="event-dot" :class="`event-${eventType(event.status)}`"></span>
          <div class="event-main">
            <div class="event-heading"><strong>{{ stageLabel(event.stage) }}</strong><n-tag size="small" :type="eventType(event.status)" :bordered="false">{{ event.status }}</n-tag></div>
            <p>{{ event.message }}</p>
            <small>第 {{ event.attempt }} 次 · {{ formatDate(event.createdAt) }}</small>
            <code v-if="event.detail && Object.keys(event.detail).length">{{ JSON.stringify(event.detail) }}</code>
          </div>
        </article>
      </div>
      <div v-else class="process-empty">这份历史文档还没有审计记录；重新处理一次后会开始记录。</div>
    </n-modal>

    <n-modal v-model:show="showUpload" preset="card" title="上传知识库文档" style="width: min(560px, calc(100vw - 32px))" :bordered="false">
      <n-form label-placement="top">
        <n-form-item label="文件">
          <n-upload v-model:file-list="uploadFiles" :default-upload="false" :max="1" accept=".pdf,.doc,.docx,.md,.markdown,.txt">
            <n-button>选择文件</n-button>
          </n-upload>
        </n-form-item>
        <n-form-item label="标题（可选）"><n-input v-model:value="uploadForm.title" placeholder="默认使用文件名" /></n-form-item>
        <n-form-item label="分类（可选）"><n-input v-model:value="uploadForm.category" placeholder="例如：预约规则、设备说明" /></n-form-item>
        <n-form-item label="标签（可选）"><n-input v-model:value="uploadForm.tags" placeholder="用逗号分隔，例如：靶车,安全规范" /></n-form-item>
        <n-form-item label="可见范围">
          <n-select v-model:value="uploadForm.visibility" :options="visibilityOptions" />
        </n-form-item>
        <n-form-item v-if="uploadForm.visibility === 'SPECIFIED_USERS'" label="授权用户 ID">
          <n-input v-model:value="uploadForm.allowedUserIdsText" placeholder="多个用户 ID 用英文逗号分隔，例如：12,18" />
        </n-form-item>
      </n-form>
      <p class="upload-note"><n-icon :component="RefreshOutline" /> 访问范围会在后端筛选文档 ID 后再传给向量检索，模型无法绕过该限制。</p>
      <template #footer><n-space justify="end"><n-button @click="showUpload = false">取消</n-button><n-button type="primary" :loading="submitting" @click="submitUpload">开始处理</n-button></n-space></template>
    </n-modal>
  </main>
</template>

<style scoped>
.knowledge-page { min-height: 100%; max-width: 1360px; margin: 0 auto; color: #172b33; }
.knowledge-hero { position: relative; overflow: hidden; display: flex; justify-content: space-between; gap: 32px; padding: 38px 42px; margin-bottom: 24px; color: #eef8f8; background: linear-gradient(118deg, #123b4a 0%, #18596b 55%, #277477 100%); border-radius: 18px; box-shadow: 0 18px 42px rgba(17, 62, 74, .16); }
.knowledge-hero::after { content: ''; position: absolute; width: 280px; height: 280px; right: 110px; top: -180px; border: 1px solid rgba(235, 249, 246, .32); border-radius: 50%; box-shadow: 0 0 0 28px rgba(235, 249, 246, .07), 0 0 0 56px rgba(235, 249, 246, .05); }
.eyebrow { margin: 0 0 12px; font: 700 11px/1.1 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .16em; color: #9ed2cd; }
h1 { position: relative; margin: 0; font-size: clamp(28px, 3.3vw, 48px); line-height: 1.08; letter-spacing: -.055em; color: #fff; }
h1 em { color: #a9e2d4; font-family: Georgia, 'Times New Roman', serif; font-weight: 400; }
.hero-copy { max-width: 530px; margin: 16px 0 0; line-height: 1.7; color: #c9e1e1; }
.hero-stat { position: relative; z-index: 1; min-width: 142px; align-self: flex-end; padding: 18px 20px; border: 1px solid rgba(223, 246, 240, .28); background: rgba(6, 38, 49, .22); backdrop-filter: blur(8px); }
.hero-stat span, .hero-stat small { display: block; font: 700 10px/1.2 ui-monospace, monospace; letter-spacing: .1em; color: #a9e2d4; }.hero-stat strong { display: block; margin: 3px 0; font-size: 34px; color: #fff; }
.workspace-card { padding: 4px; background: #fff; border-radius: 16px; box-shadow: 0 8px 30px rgba(21, 56, 67, .08); }.document-table { margin-top: 22px; }.empty-docs { padding: 64px 0; }
:deep(.doc-cell) { display: flex; align-items: center; gap: 11px; }.doc-title { font-weight: 650; color: #16343f; }.doc-meta { margin-top: 3px; font-size: 12px; color: #789099; }
.upload-note { display: flex; gap: 7px; align-items: center; margin: 4px 0; color: #708890; font-size: 13px; }
.quality-overview { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }.quality-overview > div { padding: 12px; background: #f2f8f7; border-radius: 10px; }.quality-overview small, .quality-overview strong { display: block; }.quality-overview small { color: #789099; font-size: 12px; }.quality-overview strong { overflow: hidden; margin-top: 4px; color: #16343f; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }.quality-summary { margin: 0 0 12px; color: #3d666c; font-size: 13px; }.process-error { padding: 9px 11px; margin: 0 0 12px; border-left: 3px solid #d85151; background: #fff3f2; color: #a23838; font-size: 13px; }.process-timeline { padding: 4px 0; }.process-event { position: relative; display: flex; gap: 12px; padding: 0 0 18px; }.process-event:not(:last-child)::before { content: ''; position: absolute; left: 5px; top: 13px; bottom: -3px; width: 1px; background: #d5e3e1; }.event-dot { position: relative; z-index: 1; flex: 0 0 11px; height: 11px; margin-top: 5px; border-radius: 50%; background: #92a8ad; }.event-warning { background: #d69b31; }.event-info { background: #3682a0; }.event-success { background: #32946d; }.event-error { background: #d85151; }.event-main { min-width: 0; flex: 1; }.event-heading { display: flex; align-items: center; gap: 8px; }.event-main p { margin: 5px 0; color: #4f6870; font-size: 13px; }.event-main small { color: #80979d; font-size: 12px; }.event-main code { display: block; overflow: auto; padding: 7px 8px; margin-top: 7px; border-radius: 6px; background: #f5f8f8; color: #557079; font-size: 11px; }.process-empty { padding: 24px 0; color: #789099; text-align: center; }
@media (max-width: 720px) { .knowledge-hero { padding: 28px; flex-direction: column; }.hero-stat { align-self: flex-start; }.knowledge-page { margin: -8px; }.quality-overview { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
