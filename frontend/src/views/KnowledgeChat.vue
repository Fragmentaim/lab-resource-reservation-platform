<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  NBadge, NButton, NEmpty, NIcon, NInput, NScrollbar, NSpin, NTag, NTooltip, useMessage
} from 'naive-ui'
import {
  AddOutline, ArrowUpOutline, BookOutline, ChatbubbleEllipsesOutline,
  CalendarOutline, ChevronForwardOutline, DocumentTextOutline, FlashOutline, LayersOutline,
  SparklesOutline, ThumbsDownOutline, ThumbsUpOutline, TrashOutline
} from '@vicons/ionicons5'
import {
  askKnowledgeQuestion, deleteKnowledgeSession, fetchKnowledgeRecords,
  fetchKnowledgeSessions, fetchMyReservationAssistantContext, findAvailableResourceSlots,
  previewReservationCancellation, submitKnowledgeFeedback
} from '@/api/knowledge'

const message = useMessage()
const question = ref('')
const asking = ref(false)
const loadingSessions = ref(false)
const messages = ref([])
const sessions = ref([])
const sessionId = ref('')
const scrollRef = ref(null)
const activeEvidence = ref(null)
const reservationContext = ref(null)
const loadingReservationContext = ref(false)
const resourceKeyword = ref('')
const availabilityResult = ref(null)
const loadingAvailability = ref(false)
const cancellationPreview = ref(null)

const hasMessages = computed(() => messages.value.length > 0)
const evidenceCount = computed(() => messages.value.reduce((total, item) => total + (item.sources?.length || 0), 0))

function formatTime(value) {
  return value ? new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) : ''
}

function formatSessionTime(value) {
  if (!value) return ''
  const date = new Date(value)
  const today = new Date()
  return date.toDateString() === today.toDateString()
    ? formatTime(value)
    : `${date.getMonth() + 1}/${date.getDate()}`
}

function sourceKey(source) {
  return `${source.documentId}-${source.chunkId || source.chunkIndex || 'source'}`
}

async function loadSessions() {
  try {
    loadingSessions.value = true
    sessions.value = await fetchKnowledgeSessions()
  } catch (error) {
    message.error(error.message || '加载会话失败')
  } finally {
    loadingSessions.value = false
  }
}

async function loadReservationContext() {
  try {
    loadingReservationContext.value = true
    reservationContext.value = await fetchMyReservationAssistantContext()
  } catch (error) {
    reservationContext.value = null
  } finally {
    loadingReservationContext.value = false
  }
}

async function findAvailability() {
  try {
    loadingAvailability.value = true
    availabilityResult.value = await findAvailableResourceSlots({ keyword: resourceKeyword.value || undefined, limit: 3 })
  } catch (error) {
    availabilityResult.value = null
    message.error(error.message || '可用时段查询失败')
  } finally {
    loadingAvailability.value = false
  }
}

async function loadCancellationPreview(reservationId) {
  try {
    cancellationPreview.value = await previewReservationCancellation(reservationId)
  } catch (error) {
    cancellationPreview.value = null
    message.error(error.message || '取消预检失败')
  }
}

async function loadHistory() {
  if (!sessionId.value) {
    messages.value = []
    activeEvidence.value = null
    return
  }
  try {
    const result = await fetchKnowledgeRecords({ pageNum: 1, pageSize: 50, sessionId: sessionId.value })
    messages.value = (result.records || []).flatMap(record => [
      { id: `q-${record.id}`, role: 'user', content: record.question, time: record.createdAt },
      {
        id: record.id, role: 'assistant', content: record.answer, sources: record.sources || [],
        model: record.modelName, latency: record.latencyMs, questionType: record.questionType, time: record.createdAt
      }
    ])
    activeEvidence.value = messages.value.flatMap(item => item.sources || [])[0] || null
    scrollToBottom()
  } catch (error) {
    message.error(error.message || '加载会话内容失败')
  }
}

async function selectSession(id) {
  if (id === sessionId.value) return
  sessionId.value = id
  await loadHistory()
}

function startNewChat() {
  sessionId.value = ''
  messages.value = []
  question.value = ''
  activeEvidence.value = null
}

async function removeSession(event, session) {
  event.stopPropagation()
  try {
    await deleteKnowledgeSession(session.sessionId)
    if (sessionId.value === session.sessionId) startNewChat()
    await loadSessions()
    message.success('会话已从历史中移除')
  } catch (error) {
    message.error(error.message || '删除会话失败')
  }
}

async function ask() {
  const content = question.value.trim()
  if (!content || asking.value) return
  messages.value.push({ id: `local-${Date.now()}`, role: 'user', content, time: new Date().toISOString() })
  question.value = ''
  asking.value = true
  scrollToBottom()
  try {
    const answer = await askKnowledgeQuestion({ question: content, sessionId: sessionId.value || undefined })
    sessionId.value = answer.sessionId || sessionId.value
    const assistantMessage = {
      id: answer.recordId, role: 'assistant', content: answer.answer, sources: answer.sources || [],
      model: answer.modelName, latency: answer.latencyMs, contextStats: answer.contextStats, traceId: answer.traceId,
      questionType: answer.questionType, time: new Date().toISOString()
    }
    messages.value.push(assistantMessage)
    activeEvidence.value = assistantMessage.sources[0] || activeEvidence.value
    await loadSessions()
  } catch (error) {
    messages.value.push({ id: `error-${Date.now()}`, role: 'assistant', error: true, content: error.message || '问答请求失败。确认 AI 服务和知识库文档已准备好后再试。', time: new Date().toISOString() })
  } finally {
    asking.value = false
    scrollToBottom()
  }
}

async function vote(recordId, helpful) {
  if (!recordId || String(recordId).startsWith('local') || String(recordId).startsWith('error')) return
  try {
    await submitKnowledgeFeedback({ qaRecordId: recordId, helpful })
    message.success(helpful ? '已记录：回答有帮助' : '已记录：需要继续优化')
  } catch (error) {
    message.error(error.message || '提交反馈失败')
  }
}

function scrollToBottom() {
  nextTick(() => scrollRef.value?.scrollTo({ top: 999999, behavior: 'smooth' }))
}

onMounted(async () => {
  await Promise.all([loadSessions(), loadReservationContext()])
  if (sessions.value[0]?.sessionId) {
    await selectSession(sessions.value[0].sessionId)
  }
})
</script>

<template>
  <main class="rag-workbench">
    <aside class="session-sidebar">
      <div class="brand-lockup">
        <span class="brand-orb"><n-icon :component="SparklesOutline" /></span>
        <div><small>LAB BOOKING</small><strong>Evidence Desk</strong></div>
      </div>

      <n-button class="new-chat" type="primary" block @click="startNewChat">
        <template #icon><n-icon :component="AddOutline" /></template>新建检索对话
      </n-button>

      <section class="reservation-tool-card" aria-label="我的预约上下文">
        <div class="tool-card-heading"><span><n-icon :component="CalendarOutline" />预约上下文</span><i>READ ONLY</i></div>
        <n-spin v-if="loadingReservationContext" size="small" class="tool-card-loading" />
        <template v-else-if="reservationContext">
          <div class="tool-count"><strong>{{ reservationContext.activeReservationCount || 0 }}</strong><span>个进行中预约</span></div>
          <p v-if="reservationContext.upcomingReservations?.length" class="next-reservation">
            下一项 · {{ reservationContext.upcomingReservations[0].resourceName || '实验室资源' }}
          </p>
          <p v-else class="next-reservation empty">当前没有待使用的预约</p>
          <button class="refresh-tool-context" @click="loadReservationContext">刷新业务上下文</button>
          <button v-if="reservationContext.upcomingReservations?.[0]" class="refresh-tool-context" @click="loadCancellationPreview(reservationContext.upcomingReservations[0].reservationId)">取消前检查</button>
        </template>
        <p v-else class="tool-card-unavailable">预约上下文暂不可用，不影响资料问答。</p>
      </section>

      <section class="assistant-tool-card" aria-label="资源可用时段工具">
        <div class="tool-card-heading"><span><n-icon :component="LayersOutline" />可用时段</span><i>LIVE DATA</i></div>
        <div class="tool-search"><n-input v-model:value="resourceKeyword" size="tiny" placeholder="资源名称" @keydown.enter.prevent="findAvailability" /><button @click="findAvailability">查询</button></div>
        <n-spin v-if="loadingAvailability" size="small" class="tool-card-loading" />
        <template v-else-if="availabilityResult">
          <p v-if="availabilityResult.slots?.length" v-for="slot in availabilityResult.slots" :key="slot.slotId" class="availability-line">{{ slot.resourceName }} · {{ slot.remainQuota }} 个名额</p>
          <p v-else class="tool-card-unavailable">暂无匹配的未来可用时段</p>
        </template>
      </section>

      <section v-if="cancellationPreview" class="assistant-tool-card preview-card" aria-label="取消预约预检">
        <div class="tool-card-heading"><span><n-icon :component="CalendarOutline" />取消预检</span><i>CONFIRM</i></div>
        <p class="tool-card-unavailable">{{ cancellationPreview.canCancel ? '可取消：' : '不可取消：' }}{{ cancellationPreview.nextAction }}</p>
      </section>

      <div class="side-section-label"><span>会话记录</span><n-badge :value="sessions.length" :max="99" /></div>
      <n-scrollbar class="session-list">
        <n-spin v-if="loadingSessions" size="small" class="session-loading" />
        <div v-for="session in sessions" :key="session.sessionId" class="session-item" :class="{ active: session.sessionId === sessionId }" role="button" tabindex="0" @click="selectSession(session.sessionId)" @keydown.enter="selectSession(session.sessionId)">
          <n-icon :component="ChatbubbleEllipsesOutline" />
          <span class="session-content"><strong>{{ session.title || '新对话' }}</strong><small>{{ session.turnCount || 0 }} 轮 · {{ formatSessionTime(session.lastMessageAt) }}</small></span>
          <n-tooltip><template #trigger><n-button text size="tiny" class="delete-session" @click="removeSession($event, session)"><n-icon :component="TrashOutline" /></n-button></template>删除会话</n-tooltip>
        </div>
        <n-empty v-if="!loadingSessions && !sessions.length" size="small" description="还没有对话记录" class="empty-sessions" />
      </n-scrollbar>

      <div class="knowledge-pulse"><span class="pulse-dot"></span><div><strong>Grounded RAG</strong><small>仅引用已处理资料</small></div></div>
    </aside>

    <section class="conversation-stage">
      <header class="stage-header">
        <div><p>检索增强问答</p><h1>实验室资料 <em>证据助手</em></h1></div>
        <div class="header-signals"><span><n-icon :component="LayersOutline" /> {{ evidenceCount }} 条证据</span><span><n-icon :component="FlashOutline" /> TokenMP</span></div>
      </header>

      <n-scrollbar ref="scrollRef" class="message-scroll">
        <div v-if="!hasMessages" class="empty-stage">
          <div class="empty-graphic"><i></i><i></i><i></i><n-icon :component="BookOutline" /></div>
          <p class="eyebrow">ASK WITH EVIDENCE</p>
          <h2>每次回答，都能回到原始资料。</h2>
          <p>上传后的规程、说明和预约资料会被检索；右侧会展示本次回答实际使用的证据片段。</p>
          <div class="prompt-chips"><button @click="question = '实验室资源预约有哪些规则？'">预约规则 <n-icon :component="ChevronForwardOutline" /></button><button @click="question = '设备使用前需要检查哪些事项？'">设备检查 <n-icon :component="ChevronForwardOutline" /></button></div>
        </div>

        <div v-else class="message-list">
          <article v-for="item in messages" :key="item.id" class="message" :class="item.role">
            <div class="message-label"><span>{{ item.role === 'user' ? 'YOU' : 'LAB / AI' }}</span>{{ formatTime(item.time) }}</div>
            <div class="bubble" :class="{ failed: item.error }">{{ item.content }}</div>
            <div v-if="item.role === 'assistant' && item.sources?.length" class="evidence-row">
              <button v-for="source in item.sources" :key="sourceKey(source)" class="evidence-chip" :class="{ selected: sourceKey(source) === sourceKey(activeEvidence || {}) }" @click="activeEvidence = source">
                <n-icon :component="DocumentTextOutline" /><span>{{ source.documentTitle || `文档 #${source.documentId}` }}</span><b>{{ Number(source.score || 0).toFixed(2) }}</b>
              </button>
            </div>
            <div v-if="item.role === 'assistant' && item.contextStats?.tool_calls?.length" class="tool-trace-row">
              <span v-for="call in item.contextStats.tool_calls" :key="call.tool_trace_id" class="tool-trace-chip"><n-icon :component="FlashOutline" />{{ call.tool_name }} · {{ call.result }}</span>
            </div>
            <div v-if="item.role === 'assistant' && !item.error" class="answer-meta">
              <span>{{ item.model || 'AI 模型' }}<template v-if="item.latency"> · {{ item.latency }}ms</template><template v-if="item.contextStats?.selected_source_count"> · {{ item.contextStats.selected_source_count }} 条命中</template></span>
              <n-tooltip><template #trigger><n-button text size="tiny" @click="vote(item.id, true)"><n-icon :component="ThumbsUpOutline" /></n-button></template>有帮助</n-tooltip>
              <n-tooltip><template #trigger><n-button text size="tiny" @click="vote(item.id, false)"><n-icon :component="ThumbsDownOutline" /></n-button></template>没解决</n-tooltip>
            </div>
          </article>
          <div v-if="asking" class="thinking"><n-spin size="small" /> 正在检索、校验并组织回答…</div>
        </div>
      </n-scrollbar>

      <div class="composer-shell">
        <n-input v-model:value="question" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" placeholder="问一个和实验室、预约或设备规范有关的问题" @keydown.enter.exact.prevent="ask" />
        <n-button type="primary" circle :loading="asking" :disabled="!question.trim()" @click="ask"><template #icon><n-icon :component="ArrowUpOutline" /></template></n-button>
        <span>Enter 发送 · Shift + Enter 换行</span>
      </div>
    </section>

    <aside class="evidence-inspector">
      <div class="inspector-top"><p>RETRIEVAL TRACE</p><n-tag size="small" :bordered="false" type="success">可追溯</n-tag></div>
      <template v-if="activeEvidence">
        <div class="evidence-number">{{ String(activeEvidence.chunkIndex ?? 0).padStart(2, '0') }}</div>
        <h2>{{ activeEvidence.documentTitle || `文档 #${activeEvidence.documentId}` }}</h2>
        <div class="evidence-facts"><span>来源 <b>{{ activeEvidence.retrievalSource || 'keyword' }}</b></span><span v-if="activeEvidence.pageNo">页码 <b>第 {{ activeEvidence.pageNo }} 页</b></span><span>相关度 <b>{{ Number(activeEvidence.score || 0).toFixed(2) }}</b></span></div>
        <blockquote>{{ activeEvidence.excerpt }}</blockquote>
        <div class="trace-line"><i></i><span>此片段已作为回答上下文</span></div>
      </template>
      <div v-else class="inspector-empty"><n-icon :component="DocumentTextOutline" /><h2>证据会显示在这里</h2><p>发送问题后，选择回答下方的文档标签，即可查看检索片段和相关度。</p></div>
      <div class="inspector-footer"><n-icon :component="BookOutline" /> 你的知识库 · {{ sessions.length }} 个历史会话</div>
    </aside>
  </main>
</template>

<style scoped>
.rag-workbench { --ink: #172a36; --muted: #71808a; --line: #e6ebef; --canvas: #f5f7f8; --blue: #276ef1; --mint: #32b98a; min-height: calc(100vh - 48px); display: grid; grid-template-columns: 242px minmax(0, 1fr) 284px; overflow: hidden; border: 1px solid #e1e7eb; border-radius: 20px; background: var(--canvas); box-shadow: 0 18px 46px rgba(22, 45, 61, .09); color: var(--ink); }
.session-sidebar { display: flex; flex-direction: column; min-height: 0; padding: 22px 14px 14px; border-right: 1px solid var(--line); background: #fff; }.brand-lockup { display: flex; align-items: center; gap: 9px; padding: 3px 7px 23px; }.brand-orb { display: grid; place-items: center; width: 30px; height: 30px; color: #fff; border-radius: 10px 10px 10px 3px; background: linear-gradient(135deg, #276ef1, #5d94f6); box-shadow: 0 7px 15px rgba(39, 110, 241, .23); }.brand-lockup small, .brand-lockup strong { display: block; }.brand-lockup small { font: 700 9px/1.1 ui-monospace, monospace; letter-spacing: .13em; color: #8796a0; }.brand-lockup strong { margin-top: 3px; font-size: 14px; letter-spacing: -.02em; }.new-chat { border-radius: 10px; box-shadow: 0 7px 16px rgba(39, 110, 241, .18); }.reservation-tool-card, .assistant-tool-card { position: relative; overflow: hidden; margin: 14px 0 2px; padding: 12px; border: 1px solid #dce9ff; border-radius: 11px 11px 11px 3px; background: linear-gradient(135deg, #f7fbff, #edf5ff); }.reservation-tool-card::after { content: ''; position: absolute; right: -18px; bottom: -23px; width: 70px; height: 70px; border: 1px solid #c4dafb; border-radius: 50%; box-shadow: 0 0 0 11px rgba(196, 218, 251, .25); }.assistant-tool-card { margin-top: 9px; border-color: #e4e8d8; background: linear-gradient(135deg, #fbfcf6, #f4f8e9); }.preview-card { border-color: #f2dfbb; background: #fffaf0; }.tool-card-heading { display: flex; align-items: center; justify-content: space-between; color: #4e6e8d; font: 700 10px ui-monospace, monospace; letter-spacing: .08em; }.tool-card-heading span { display: inline-flex; gap: 5px; align-items: center; }.tool-card-heading i { color: #7694af; font-size: 8px; font-style: normal; }.tool-count { display: flex; align-items: baseline; gap: 6px; margin-top: 12px; }.tool-count strong { color: #2054aa; font: 700 28px/.9 ui-monospace, monospace; letter-spacing: -.08em; }.tool-count span { color: #55728d; font-size: 11px; }.next-reservation { position: relative; z-index: 1; margin: 10px 0 6px; overflow: hidden; color: #3d5870; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.next-reservation.empty, .tool-card-unavailable { color: #8398aa; }.refresh-tool-context { position: relative; z-index: 1; margin-right: 9px; padding: 0; border: 0; color: #1f65c7; font-size: 10px; background: transparent; cursor: pointer; }.refresh-tool-context:hover { text-decoration: underline; }.tool-search { display: flex; gap: 6px; margin-top: 9px; }.tool-search button { padding: 0 7px; border: 0; border-radius: 5px; color: #fff; font-size: 10px; background: #527b59; cursor: pointer; }.availability-line { margin: 7px 0 0; overflow: hidden; color: #4e6552; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.tool-card-loading { display: block; margin: 22px auto; }.tool-card-unavailable { margin: 12px 0 2px; font-size: 11px; line-height: 1.5; }.side-section-label { display: flex; justify-content: space-between; align-items: center; padding: 25px 7px 9px; color: #94a1a9; font: 700 10px/1 ui-monospace, monospace; letter-spacing: .1em; }.session-list { flex: 1; min-height: 100px; }.session-loading { display: block; margin: 20px auto; }.session-item { width: 100%; display: flex; gap: 9px; align-items: center; padding: 10px 7px; text-align: left; border: 0; border-radius: 9px; color: #8b99a2; background: transparent; cursor: pointer; }.session-item:hover { color: var(--ink); background: #f3f6f8; }.session-item.active { color: #1e5cd0; background: #edf4ff; }.session-content { min-width: 0; flex: 1; }.session-content strong, .session-content small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.session-content strong { font-size: 12px; font-weight: 650; }.session-content small { margin-top: 3px; font-size: 10px; color: #9ba7ae; }.delete-session { opacity: 0; color: #a5afb5; }.session-item:hover .delete-session { opacity: 1; }.empty-sessions { margin-top: 28px; }.knowledge-pulse { display: flex; gap: 8px; align-items: center; padding: 13px 7px 3px; border-top: 1px solid var(--line); }.pulse-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--mint); box-shadow: 0 0 0 4px rgba(50, 185, 138, .13); }.knowledge-pulse strong, .knowledge-pulse small { display: block; }.knowledge-pulse strong { font-size: 11px; }.knowledge-pulse small { margin-top: 2px; font-size: 10px; color: var(--muted); }
.conversation-stage { display: flex; flex-direction: column; min-width: 0; min-height: 0; background: #fbfcfd; }.stage-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 22px 30px 17px; border-bottom: 1px solid var(--line); background: rgba(255, 255, 255, .78); }.stage-header p, .inspector-top p, .eyebrow { margin: 0 0 5px; color: #83919b; font: 700 10px/1.1 ui-monospace, monospace; letter-spacing: .13em; }.stage-header h1 { margin: 0; font-size: 19px; letter-spacing: -.035em; }.stage-header h1 em { color: var(--blue); font-family: Georgia, serif; font-weight: 400; }.header-signals { display: flex; gap: 8px; flex-wrap: wrap; }.header-signals span { display: inline-flex; gap: 5px; align-items: center; padding: 5px 8px; border: 1px solid #e6ebee; border-radius: 20px; color: #71808a; font-size: 11px; background: #fff; }.message-scroll { flex: 1; padding: 28px 30px; }.message-list { max-width: 760px; margin: 0 auto; }.message { margin: 0 0 24px; }.message.user { padding-left: 17%; }.message-label { display: flex; gap: 8px; align-items: center; margin-bottom: 7px; color: #9aa6ad; font-size: 10px; }.message-label span { font: 700 10px ui-monospace, monospace; letter-spacing: .1em; color: #61717b; }.bubble { white-space: pre-wrap; padding: 14px 16px; border: 1px solid #e2e8eb; border-radius: 5px 14px 14px; color: #243844; line-height: 1.75; background: #fff; box-shadow: 0 3px 10px rgba(20, 45, 59, .02); }.user .bubble { color: #f8fbff; border-color: #276ef1; border-radius: 14px 5px 14px 14px; background: linear-gradient(135deg, #276ef1, #4c88f3); }.bubble.failed { color: #a74040; border-color: #f0c8c8; background: #fff7f7; }.evidence-row, .tool-trace-row { display: flex; gap: 7px; flex-wrap: wrap; margin-top: 10px; }.evidence-chip { display: inline-flex; max-width: 250px; gap: 6px; align-items: center; padding: 6px 8px; border: 1px solid #dde7ed; border-radius: 7px; color: #527080; background: #fff; cursor: pointer; }.evidence-chip:hover, .evidence-chip.selected { border-color: #8ab4fd; color: #1b5fd9; background: #eff5ff; }.evidence-chip span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }.evidence-chip b { color: #8394a1; font: 700 10px ui-monospace, monospace; }.tool-trace-chip { display: inline-flex; align-items: center; gap: 4px; padding: 5px 8px; border: 1px solid #d7e7cf; border-radius: 7px; color: #4e7452; font: 700 10px ui-monospace, monospace; background: #f6fbf3; }.answer-meta { display: flex; gap: 6px; align-items: center; margin-top: 8px; color: #8d9aa3; font-size: 10px; }.answer-meta span { margin-right: auto; }.thinking { display: flex; gap: 8px; align-items: center; margin: 8px 0 24px; color: #6e7f89; font-size: 12px; }.empty-stage { max-width: 620px; min-height: 420px; display: flex; flex-direction: column; align-items: flex-start; justify-content: center; margin: 0 auto; }.empty-graphic { position: relative; display: grid; place-items: center; width: 58px; height: 58px; margin-bottom: 23px; border-radius: 18px 18px 18px 4px; color: var(--blue); font-size: 27px; background: #eaf2ff; }.empty-graphic i { position: absolute; width: 7px; height: 7px; border-radius: 50%; background: #91b9fc; }.empty-graphic i:nth-child(1) { top: -5px; right: 9px; }.empty-graphic i:nth-child(2) { right: -11px; bottom: 14px; }.empty-graphic i:nth-child(3) { left: -7px; bottom: 4px; }.empty-stage h2 { max-width: 470px; margin: 0; font-size: clamp(26px, 3vw, 38px); letter-spacing: -.055em; }.empty-stage > p:not(.eyebrow) { max-width: 540px; margin: 13px 0 20px; color: #70808a; line-height: 1.75; }.prompt-chips { display: flex; gap: 9px; flex-wrap: wrap; }.prompt-chips button { display: inline-flex; gap: 7px; align-items: center; padding: 9px 11px; border: 1px solid #dbe4ea; border-radius: 8px; color: #38505f; background: #fff; cursor: pointer; }.prompt-chips button:hover { border-color: #80aafb; color: #1c5fda; }.composer-shell { position: relative; display: flex; gap: 10px; align-items: flex-end; padding: 16px 30px 26px; border-top: 1px solid var(--line); background: #fff; }.composer-shell :deep(.n-input) { border-radius: 12px; }.composer-shell :deep(textarea) { line-height: 1.6; }.composer-shell :deep(.n-button) { width: 41px; height: 41px; flex: 0 0 auto; }.composer-shell > span { position: absolute; left: 43px; bottom: 7px; color: #a1acb2; font-size: 10px; }
.evidence-inspector { display: flex; flex-direction: column; padding: 23px 20px 16px; border-left: 1px solid var(--line); background: #fff; }.inspector-top { display: flex; justify-content: space-between; align-items: flex-start; }.evidence-number { margin: 30px 0 14px; color: #bfd3ea; font: 700 48px/.8 ui-monospace, monospace; letter-spacing: -.09em; }.evidence-inspector h2 { margin: 0; font-size: 18px; line-height: 1.25; letter-spacing: -.035em; }.evidence-facts { display: grid; gap: 8px; margin: 22px 0; }.evidence-facts span { display: flex; justify-content: space-between; padding-bottom: 7px; border-bottom: 1px solid #edf0f2; color: #8b99a2; font-size: 11px; }.evidence-facts b { color: #415560; font-weight: 650; }.evidence-inspector blockquote { margin: 0; padding: 13px 14px; border-left: 3px solid #7eabfa; color: #536873; font-size: 12px; line-height: 1.8; background: #f5f8fc; }.trace-line { display: flex; gap: 7px; align-items: center; margin-top: 13px; color: #71808a; font-size: 10px; }.trace-line i { display: block; width: 8px; height: 8px; border-radius: 50%; background: var(--mint); box-shadow: 0 0 0 4px rgba(50, 185, 138, .12); }.inspector-empty { display: flex; flex: 1; flex-direction: column; align-items: center; justify-content: center; padding: 0 8px; text-align: center; color: #8c9aa3; }.inspector-empty :deep(.n-icon) { margin-bottom: 14px; color: #a9c2e2; font-size: 34px; }.inspector-empty h2 { color: #607581; font-size: 15px; }.inspector-empty p { margin: 9px 0 0; font-size: 12px; line-height: 1.7; }.inspector-footer { display: flex; gap: 6px; align-items: center; padding-top: 14px; border-top: 1px solid var(--line); color: #8d9aa3; font-size: 10px; }
@media (max-width: 1100px) { .rag-workbench { grid-template-columns: 222px minmax(0, 1fr); }.evidence-inspector { display: none; } }
@media (max-width: 720px) { .rag-workbench { min-height: calc(100vh - 28px); display: block; border-radius: 13px; }.session-sidebar { display: none; }.stage-header { padding: 18px; }.header-signals { display: none; }.message-scroll { padding: 20px 16px; }.message.user { padding-left: 7%; }.empty-stage { min-height: 400px; }.composer-shell { padding: 14px 16px 25px; }.composer-shell > span { left: 28px; } }
</style>
