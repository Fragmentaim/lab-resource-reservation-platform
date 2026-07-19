<script setup>
import { computed, h, nextTick, onMounted, ref } from 'vue'
import { NButton, NCard, NEmpty, NIcon, NInput, NScrollbar, NSpace, NSpin, NTag, NTooltip, useMessage } from 'naive-ui'
import { AddOutline, ArrowUpOutline, BookOutline, ChatbubblesOutline, DocumentTextOutline, RefreshOutline, ThumbsDownOutline, ThumbsUpOutline } from '@vicons/ionicons5'
import { askKnowledgeQuestion, fetchKnowledgeRecords, submitKnowledgeFeedback } from '@/api/knowledge'

const message = useMessage()
const question = ref('')
const asking = ref(false)
const messages = ref([])
const scrollRef = ref(null)
const sessionId = ref('')

const hasMessages = computed(() => messages.value.length > 0)

function formatTime(value) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

async function loadHistory() {
  try {
    const result = await fetchKnowledgeRecords({ pageNum: 1, pageSize: 30, sessionId: sessionId.value })
    messages.value = (result.records || []).flatMap(record => [
      { id: `q-${record.id}`, role: 'user', content: record.question, time: record.createdAt },
      { id: record.id, role: 'assistant', content: record.answer, sources: record.sources || [], model: record.modelName, latency: record.latencyMs, time: record.createdAt }
    ])
    scrollToBottom()
  } catch (error) {
    // A new session has no history; errors are only useful after the user has sent a question.
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
    messages.value.push({ id: answer.recordId, role: 'assistant', content: answer.answer, sources: answer.sources || [], model: answer.modelName, latency: answer.latencyMs, time: new Date().toISOString() })
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
    message.success(helpful ? '已记录为有帮助' : '已记录反馈')
  } catch (error) {
    message.error(error.message || '提交反馈失败')
  }
}

function resetSession() {
  sessionId.value = ''
  messages.value = []
  question.value = ''
}

function scrollToBottom() {
  nextTick(() => scrollRef.value?.scrollTo({ top: 999999, behavior: 'smooth' }))
}

onMounted(loadHistory)
</script>

<template>
  <main class="chat-page">
    <aside class="chat-rail">
      <div>
        <div class="rail-mark"><n-icon :component="ChatbubblesOutline" /> <span>LAB / AI</span></div>
        <h1>从资料里，<br><em>找到依据。</em></h1>
        <p>回答只基于已处理的知识库文档；每一条证据都保留来源、页码和检索得分。</p>
      </div>
      <div class="rail-footer"><span class="signal"></span> 知识库检索已启用</div>
    </aside>

    <section class="chat-workspace">
      <header class="chat-header">
        <div><p class="eyebrow">KNOWLEDGE ASSISTANT</p><strong>实验室智能问答</strong></div>
        <n-button secondary @click="resetSession"><template #icon><n-icon :component="AddOutline" /></template>新对话</n-button>
      </header>

      <n-scrollbar ref="scrollRef" class="message-scroll">
        <div v-if="!hasMessages" class="welcome">
          <div class="welcome-icon"><n-icon :component="BookOutline" /></div>
          <h2>先问一个和实验室有关的问题</h2>
          <p>例如：预约规则是什么？靶车使用前需要检查哪些事项？</p>
          <n-space wrap justify="center"><n-button size="small" @click="question = '实验室资源预约有哪些规则？'">预约规则</n-button><n-button size="small" @click="question = '靶车使用前需要检查哪些事项？'">设备检查</n-button></n-space>
        </div>
        <div v-else class="message-list">
          <article v-for="item in messages" :key="item.id" class="message" :class="item.role">
            <div class="message-label">{{ item.role === 'user' ? '你' : 'LAB / AI' }} <span>{{ formatTime(item.time) }}</span></div>
            <div class="bubble" :class="{ failed: item.error }">{{ item.content }}</div>
            <div v-if="item.role === 'assistant' && item.sources?.length" class="sources">
              <div class="source-heading"><n-icon :component="DocumentTextOutline" /> 回答依据</div>
              <div v-for="source in item.sources" :key="`${source.documentId}-${source.chunkId}`" class="source-card">
                <div><strong>{{ source.documentTitle || `文档 #${source.documentId}` }}</strong><span v-if="source.pageNo"> · 第 {{ source.pageNo }} 页</span></div>
                <p>{{ source.excerpt }}</p>
                <n-tag size="tiny" :bordered="false">{{ source.retrievalSource || '检索' }} {{ source.score?.toFixed?.(2) }}</n-tag>
              </div>
            </div>
            <div v-if="item.role === 'assistant' && !item.error" class="answer-meta"><span>{{ item.model || 'AI 模型' }}<template v-if="item.latency"> · {{ item.latency }}ms</template></span><n-tooltip><template #trigger><n-button text size="tiny" @click="vote(item.id, true)"><n-icon :component="ThumbsUpOutline" /></n-button></template>有帮助</n-tooltip><n-tooltip><template #trigger><n-button text size="tiny" @click="vote(item.id, false)"><n-icon :component="ThumbsDownOutline" /></n-button></template>没解决</n-tooltip></div>
          </article>
          <div v-if="asking" class="thinking"><n-spin size="small" /> 正在检索资料并生成回答…</div>
        </div>
      </n-scrollbar>

      <div class="composer"><n-input v-model:value="question" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" placeholder="输入问题，Enter 发送，Shift + Enter 换行" @keydown.enter.exact.prevent="ask" /><n-button type="primary" circle :loading="asking" :disabled="!question.trim()" @click="ask"><template #icon><n-icon :component="ArrowUpOutline" /></template></n-button></div>
    </section>
  </main>
</template>

<style scoped>
.chat-page { min-height: calc(100vh - 48px); display: grid; grid-template-columns: minmax(250px, 30%) 1fr; overflow: hidden; border-radius: 18px; background: #f7fbfa; box-shadow: 0 10px 34px rgba(22, 67, 70, .08); }.chat-rail { display: flex; flex-direction: column; justify-content: space-between; padding: 36px 30px; color: #e8f4f1; background: #173d44; }.rail-mark { display: flex; gap: 8px; align-items: center; font: 700 11px ui-monospace, monospace; letter-spacing: .14em; color: #a4d6ca; }.chat-rail h1 { margin: 42px 0 16px; font-size: clamp(28px, 3vw, 42px); line-height: 1.08; letter-spacing: -.05em; color: #fff; }.chat-rail h1 em { color: #9cdbc8; font-family: Georgia, serif; font-weight: 400; }.chat-rail p { line-height: 1.75; color: #b8d4ce; }.rail-footer { font-size: 12px; color: #a9cbc2; }.signal { display: inline-block; width: 7px; height: 7px; margin-right: 5px; background: #7ee2bd; border-radius: 50%; box-shadow: 0 0 0 4px rgba(126, 226, 189, .12); }.chat-workspace { min-width: 0; display: flex; flex-direction: column; }.chat-header { display: flex; justify-content: space-between; align-items: center; padding: 25px 30px 18px; border-bottom: 1px solid #dceae7; }.eyebrow { margin: 0 0 5px; font: 700 10px ui-monospace, monospace; letter-spacing: .13em; color: #63858a; }.chat-header strong { color: #183941; font-size: 18px; }.message-scroll { flex: 1; padding: 26px 30px; }.welcome { min-height: 390px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; color: #628087; }.welcome-icon { display: grid; place-items: center; width: 54px; height: 54px; margin-bottom: 16px; border-radius: 16px; color: #28726e; font-size: 27px; background: #dff2eb; }.welcome h2 { margin: 0; color: #244750; }.welcome p { margin: 10px 0 20px; }.message-list { max-width: 820px; margin: 0 auto; }.message { margin-bottom: 24px; }.message.user { padding-left: 15%; }.message-label { margin-bottom: 7px; font-size: 12px; font-weight: 700; color: #638087; }.message-label span { margin-left: 7px; font-weight: 400; color: #9cb2b2; }.bubble { white-space: pre-wrap; padding: 14px 16px; border: 1px solid #dceae7; border-radius: 4px 14px 14px 14px; line-height: 1.72; color: #24424a; background: #fff; }.user .bubble { color: #f2fffb; background: #26706d; border-color: #26706d; border-radius: 14px 4px 14px 14px; }.bubble.failed { color: #a34242; background: #fff5f4; border-color: #f2c8c4; }.sources { margin-top: 12px; padding: 12px; background: #f1f8f6; border-left: 3px solid #72b99f; }.source-heading { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; font-size: 12px; font-weight: 700; color: #38696a; }.source-card { padding: 8px 0; border-top: 1px solid #dcece6; font-size: 12px; color: #52737a; }.source-card:first-of-type { border-top: 0; }.source-card strong { color: #294d54; }.source-card p { margin: 5px 0; line-height: 1.5; }.answer-meta { display: flex; gap: 7px; align-items: center; margin-top: 7px; color: #87a0a1; font-size: 11px; }.answer-meta span { margin-right: auto; }.thinking { display: flex; align-items: center; gap: 8px; color: #638087; font-size: 13px; }.composer { display: flex; gap: 10px; align-items: flex-end; padding: 18px 30px 24px; border-top: 1px solid #dceae7; background: #fff; }.composer :deep(textarea) { line-height: 1.55; }.composer :deep(.n-button) { flex: 0 0 auto; width: 40px; height: 40px; }
@media (max-width: 840px) { .chat-page { grid-template-columns: 1fr; }.chat-rail { display: block; min-height: auto; padding: 22px; }.chat-rail h1 { margin: 14px 0 7px; font-size: 28px; }.chat-rail p, .rail-footer { display: none; }.message.user { padding-left: 5%; }.message-scroll, .composer { padding-left: 16px; padding-right: 16px; } }
</style>
