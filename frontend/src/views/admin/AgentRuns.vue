<script setup>
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton, NCard, NDataTable, NDrawer, NDrawerContent, NEmpty, NIcon, NSelect,
  NSpace, NTag, useMessage
} from 'naive-ui'
import { FlashOutline, GitNetworkOutline, PulseOutline, ReloadOutline } from '@vicons/ionicons5'
import { fetchAgentRunContext, fetchAgentRunSteps, fetchAgentRuns } from '@/api/knowledge'

const message = useMessage()
const loading = ref(false)
const runs = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(15)
const route = ref(null)
const status = ref(null)
const showDetail = ref(false)
const selectedRun = ref(null)
const steps = ref([])
const stepsLoading = ref(false)
const contextTrace = ref(null)

const routeOptions = [
  { label: '全部路径', value: null },
  { label: '工具调用', value: 'TOOL' },
  { label: '知识检索', value: 'KB_RAG' },
  { label: '处理中', value: 'PENDING' }
]

const statusOptions = [
  { label: '全部状态', value: null },
  { label: '已完成', value: 'SUCCEEDED' },
  { label: '运行中', value: 'RUNNING' },
  { label: '失败', value: 'FAILED' }
]

const summary = computed(() => ({
  total: total.value,
  tool: runs.value.filter(item => item.route === 'TOOL').length,
  rag: runs.value.filter(item => item.route === 'KB_RAG').length,
  failed: runs.value.filter(item => item.status === 'FAILED').length
}))

function routeLabel(value) {
  return ({ TOOL: '业务工具', KB_RAG: '知识检索', PENDING: '待路由' })[value] || value || '待路由'
}

function routeType(value) {
  return ({ TOOL: 'success', KB_RAG: 'info', PENDING: 'warning' })[value] || 'default'
}

function statusLabel(value) {
  return ({ SUCCEEDED: '完成', RUNNING: '运行中', FAILED: '失败' })[value] || value
}

function statusType(value) {
  return ({ SUCCEEDED: 'success', RUNNING: 'warning', FAILED: 'error' })[value] || 'default'
}

function formatDate(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function formatLatency(value) {
  return `${Number(value || 0).toLocaleString()} ms`
}

async function loadRuns() {
  try {
    loading.value = true
    const data = await fetchAgentRuns({ pageNum: page.value, pageSize: pageSize.value, route: route.value || undefined, status: status.value || undefined })
    runs.value = data.records || []
    total.value = Number(data.total || 0)
  } catch (error) {
    message.error(error.message || '加载 AI 执行记录失败')
  } finally {
    loading.value = false
  }
}

async function inspectRun(run) {
  selectedRun.value = run
  steps.value = []
  contextTrace.value = null
  showDetail.value = true
  try {
    stepsLoading.value = true
    const [runSteps, trace] = await Promise.all([
      fetchAgentRunSteps(run.traceId),
      fetchAgentRunContext(run.traceId)
    ])
    steps.value = runSteps
    contextTrace.value = trace || null
  } catch (error) {
    message.error(error.message || '加载执行步骤失败')
  } finally {
    stepsLoading.value = false
  }
}

const columns = [
  { title: '执行时间', key: 'createdAt', width: 174, render: row => h('span', { class: 'mono muted' }, formatDate(row.createdAt)) },
  { title: '路径', key: 'route', width: 108, render: row => h(NTag, { type: routeType(row.route), bordered: false, size: 'small' }, { default: () => routeLabel(row.route) }) },
  { title: '状态', key: 'status', width: 92, render: row => h(NTag, { type: statusType(row.status), round: true, size: 'small' }, { default: () => statusLabel(row.status) }) },
  { title: '模型 / 执行器', key: 'modelName', minWidth: 180, render: row => h('span', { class: 'model-name' }, row.modelName || '待路由') },
  { title: '步骤', key: 'stepCount', width: 76, render: row => h('span', { class: 'mono' }, String(row.stepCount ?? 0).padStart(2, '0')) },
  { title: '证据', key: 'sourceCount', width: 76, render: row => h('span', { class: 'mono' }, String(row.sourceCount ?? 0).padStart(2, '0')) },
  { title: '耗时', key: 'totalLatencyMs', width: 100, render: row => h('span', { class: 'mono' }, formatLatency(row.totalLatencyMs)) },
  { title: '', key: 'action', width: 82, render: row => h(NButton, { text: true, type: 'primary', onClick: () => inspectRun(row) }, { default: () => '展开' }) }
]

onMounted(loadRuns)
</script>

<template>
  <main class="run-ledger">
    <section class="ledger-head">
      <div class="head-copy">
        <p class="eyebrow"><span></span> AI OBSERVABILITY / 运行账本</p>
        <h1>每一次回答，<em>都有迹可循。</em></h1>
        <p>查看模型选路、检索证据、工具调用及其授权审计关联。这里不保存问答正文，只记录系统如何完成一次回答。</p>
      </div>
      <div class="signal-board" aria-label="当前页执行统计">
        <div><small>本次查询</small><strong>{{ summary.total }}</strong></div>
        <div><small>工具路径</small><strong>{{ summary.tool }}</strong></div>
        <div><small>检索路径</small><strong>{{ summary.rag }}</strong></div>
        <div class="failure"><small>失败</small><strong>{{ summary.failed }}</strong></div>
      </div>
    </section>

    <n-card class="ledger-card" :bordered="false">
      <div class="ledger-toolbar">
        <n-space wrap>
          <n-select v-model:value="route" :options="routeOptions" clearable style="width: 136px" @update:value="() => { page = 1; loadRuns() }" />
          <n-select v-model:value="status" :options="statusOptions" clearable style="width: 126px" @update:value="() => { page = 1; loadRuns() }" />
        </n-space>
        <n-button secondary :loading="loading" @click="loadRuns"><template #icon><n-icon :component="ReloadOutline" /></template>刷新记录</n-button>
      </div>
      <n-data-table v-if="runs.length || loading" :columns="columns" :data="runs" :loading="loading" :row-key="row => row.id" :pagination="{
        page, pageSize, itemCount: total, showSizePicker: true, pageSizes: [15, 30, 50],
        onChange: value => { page = value; loadRuns() },
        onUpdatePageSize: value => { pageSize = value; page = 1; loadRuns() }
      }" />
      <n-empty v-else description="还没有 AI 执行记录。发送一次问答后，这里会展示它的运行链路。" class="empty" />
    </n-card>

    <n-drawer v-model:show="showDetail" :width="Math.min(590, window?.innerWidth || 590)" placement="right">
      <n-drawer-content v-if="selectedRun" closable>
        <template #header>
          <div class="drawer-title"><span class="eyebrow"><i></i> RUN TRACE</span><strong>{{ routeLabel(selectedRun.route) }}</strong></div>
        </template>
        <div class="run-meta">
          <div><span>运行状态</span><n-tag :type="statusType(selectedRun.status)" size="small" round>{{ statusLabel(selectedRun.status) }}</n-tag></div>
          <div><span>模型</span><b>{{ selectedRun.modelName || '待路由' }}</b></div>
          <div><span>总耗时</span><b>{{ formatLatency(selectedRun.totalLatencyMs) }}</b></div>
          <div><span>Trace ID</span><code>{{ selectedRun.traceId }}</code></div>
        </div>
        <section v-if="contextTrace" class="context-card">
          <div class="context-card-head"><span>CONTEXT GOVERNANCE</span><n-tag :type="contextTrace.rewriteApplied ? 'info' : 'default'" size="small" :bordered="false">{{ contextTrace.rewriteApplied ? '问题已改写' : '原问题直通' }}</n-tag></div>
          <p>只展示上下文预算与证据决策，不展示会话正文、回答或知识片段。</p>
          <div class="context-metrics">
            <div><small>摘要</small><strong>{{ contextTrace.summaryTokens }}</strong><span>tokens</span></div>
            <div><small>历史</small><strong>{{ contextTrace.historyTokens }}</strong><span>tokens</span></div>
            <div><small>证据</small><strong>{{ contextTrace.evidenceTokens }}</strong><span>tokens</span></div>
            <div><small>总提示词</small><strong>{{ contextTrace.totalPromptTokens }}</strong><span>tokens</span></div>
            <div><small>采用证据</small><strong>{{ contextTrace.selectedSourceCount }}</strong><span>条</span></div>
            <div><small>裁剪候选</small><strong>{{ contextTrace.droppedSourceCount }}</strong><span>条</span></div>
          </div>
        </section>
        <div v-if="stepsLoading" class="drawer-loading"><n-icon :component="PulseOutline" /> 正在读取步骤</div>
        <div v-else-if="steps.length" class="step-rail">
          <article v-for="step in steps" :key="`${step.stepNo}-${step.name}`" class="step-card" :class="step.status.toLowerCase()">
            <div class="step-marker"><span>{{ String(step.stepNo).padStart(2, '0') }}</span></div>
            <div class="step-body">
              <div class="step-top"><n-tag :type="statusType(step.status === 'SUCCESS' ? 'SUCCEEDED' : step.status)" size="small" :bordered="false">{{ step.stepType }}</n-tag><span>{{ formatLatency(step.latencyMs) }}</span></div>
              <h3><n-icon v-if="step.stepType === 'TOOL_CALL'" :component="FlashOutline" />{{ step.name }}</h3>
              <p v-if="step.toolTraceId">工具审计：<code>{{ step.toolTraceId }}</code></p>
              <dl v-if="Object.keys(step.detail || {}).length">
                <template v-for="(value, key) in step.detail" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template>
              </dl>
            </div>
          </article>
        </div>
        <n-empty v-else description="此执行记录暂未生成步骤。" />
      </n-drawer-content>
    </n-drawer>
  </main>
</template>

<style scoped>
.run-ledger { --ink: #162634; --muted: #778796; --line: #dce5eb; --paper: #f6f9fb; --signal: #2e85bd; --mint: #178f70; max-width: 1380px; margin: 0 auto; color: var(--ink); }
.ledger-head { display: grid; grid-template-columns: minmax(0, 1fr) minmax(350px, .8fr); gap: 32px; align-items: end; padding: 32px 36px 30px; margin-bottom: 20px; overflow: hidden; background: linear-gradient(110deg, #122934, #173e50); border-radius: 18px; color: #eff8fb; box-shadow: 0 18px 42px rgba(17, 48, 65, .14); }
.eyebrow { display: inline-flex; gap: 8px; align-items: center; margin: 0 0 11px; color: #8fc8d0; font: 700 10px/1 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .14em; }.eyebrow span, .eyebrow i { width: 7px; height: 7px; border-radius: 50%; background: #55d3aa; box-shadow: 0 0 0 5px rgba(85, 211, 170, .14); }
h1 { margin: 0; font-size: clamp(27px, 3.2vw, 45px); letter-spacing: -.06em; line-height: 1.04; color: #fff; } h1 em { color: #9fe0d1; font-family: Georgia, serif; font-weight: 400; }.head-copy > p:not(.eyebrow) { max-width: 610px; margin: 14px 0 0; color: #bed2db; line-height: 1.7; }
.signal-board { display: grid; grid-template-columns: repeat(4, 1fr); border: 1px solid rgba(196, 227, 230, .18); background: rgba(6, 24, 34, .28); }.signal-board div { position: relative; padding: 17px 14px; border-right: 1px solid rgba(196, 227, 230, .15); }.signal-board div:last-child { border-right: 0; }.signal-board small { display: block; color: #9ebbc5; font: 700 9px ui-monospace, monospace; letter-spacing: .08em; }.signal-board strong { display: block; margin-top: 4px; color: #fff; font: 700 25px/1 ui-monospace, monospace; }.signal-board .failure strong { color: #ffbcab; }
.ledger-card { overflow: hidden; background: #fff; border-radius: 16px; box-shadow: 0 8px 30px rgba(28, 57, 72, .07); }.ledger-toolbar { display: flex; justify-content: space-between; gap: 15px; padding: 17px 20px; border-bottom: 1px solid #edf1f4; }.mono, code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }.muted { color: var(--muted); }.model-name { color: #355160; font-weight: 600; }.empty { padding: 72px 0; }
.drawer-title { display: grid; gap: 4px; }.drawer-title .eyebrow { margin: 0; color: #64828e; }.drawer-title strong { font-size: 20px; letter-spacing: -.035em; }.run-meta { display: grid; gap: 0; margin: 4px 0 26px; border-top: 1px solid var(--line); }.run-meta > div { display: flex; justify-content: space-between; gap: 20px; align-items: center; min-height: 41px; border-bottom: 1px solid var(--line); color: #71818c; font-size: 12px; }.run-meta b { color: #294251; font-weight: 650; }.run-meta code { max-width: 250px; overflow: hidden; color: #496b79; text-overflow: ellipsis; white-space: nowrap; }.drawer-loading { display: grid; gap: 10px; place-items: center; padding: 80px 0; color: var(--muted); }.drawer-loading :deep(.n-icon) { color: var(--signal); font-size: 27px; }
.context-card { margin: 0 0 25px; padding: 14px; border: 1px solid #d9e8ed; border-radius: 11px; background: linear-gradient(135deg, #f5fafb, #f9fbfc); }.context-card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #3f6978; font: 700 10px ui-monospace, monospace; letter-spacing: .1em; }.context-card > p { margin: 8px 0 13px; color: #718792; font-size: 11px; line-height: 1.55; }.context-metrics { display: grid; grid-template-columns: repeat(3, 1fr); border-top: 1px solid #ddebed; border-left: 1px solid #ddebed; }.context-metrics > div { min-width: 0; padding: 9px 8px; border-right: 1px solid #ddebed; border-bottom: 1px solid #ddebed; }.context-metrics small { display: block; color: #7f969f; font: 700 9px ui-monospace, monospace; letter-spacing: .04em; }.context-metrics strong { margin-right: 3px; color: #294f60; font: 700 16px/1.3 ui-monospace, monospace; }.context-metrics span { color: #80939b; font: 10px ui-monospace, monospace; }
.step-rail { position: relative; display: grid; gap: 0; }.step-rail::before { position: absolute; top: 24px; bottom: 24px; left: 16px; width: 1px; content: ''; background: #d7e4e8; }.step-card { position: relative; display: grid; grid-template-columns: 34px 1fr; gap: 12px; padding: 0 0 19px; }.step-marker { position: relative; z-index: 1; display: grid; place-items: center; width: 33px; height: 33px; border: 1px solid #a8bec7; border-radius: 50%; color: #4f6977; background: #fff; font: 700 9px ui-monospace, monospace; }.step-card.success .step-marker, .step-card.succeeded .step-marker { border-color: #71c2a4; color: #0e795d; background: #f2fbf7; }.step-card.failed .step-marker { border-color: #ec9b9b; color: #bb4b4b; background: #fff5f5; }.step-body { padding: 11px 13px 12px; border: 1px solid #e4ecef; border-radius: 10px; background: #fff; }.step-top { display: flex; justify-content: space-between; gap: 12px; color: #81919a; font: 700 10px ui-monospace, monospace; }.step-body h3 { display: flex; gap: 6px; align-items: center; margin: 8px 0 6px; color: #233b48; font-size: 14px; }.step-body h3 :deep(.n-icon) { color: #d9922b; }.step-body p { margin: 0; color: #7b8d97; font-size: 11px; }.step-body p code { color: #487384; } dl { display: grid; grid-template-columns: auto 1fr; gap: 4px 10px; margin: 10px 0 0; padding-top: 9px; border-top: 1px dashed #e1e8ec; font-size: 11px; } dt { color: #8a9aa2; } dd { margin: 0; overflow-wrap: anywhere; color: #49606d; }
@media (max-width: 780px) { .ledger-head { grid-template-columns: 1fr; padding: 27px 25px; }.signal-board { width: 100%; }.signal-board div { padding: 13px 9px; }.signal-board strong { font-size: 20px; }.run-ledger { margin: -8px; }.ledger-toolbar { align-items: flex-start; flex-direction: column; } }
</style>
