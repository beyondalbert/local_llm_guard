<template>
  <div class="chat-app">
    <!-- 会话侧边栏（桌面常驻 / 移动端抽屉） -->
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="brand">
        <span class="brand-mark">L</span>
        <span>Local LLM Guard</span>
      </div>
      <el-button class="new-btn" type="primary" @click="newSession">
        <span style="font-weight:700">＋</span> 新对话
      </el-button>
      <div class="sidebar-label">历史会话</div>
      <el-scrollbar class="session-scroll">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: currentSession && currentSession.id === s.id }"
          @click="selectSession(s)"
        >
          <div class="session-title" :title="s.title">{{ s.title || '未命名会话' }}</div>
          <div class="session-meta">
            <span class="session-time">{{ shortTime(s.updateTime) }}</span>
            <span class="session-actions" @click.stop>
              <el-tooltip content="重命名" placement="top">
                <button class="act" @click="renameSession(s)">✎</button>
              </el-tooltip>
              <el-tooltip content="删除" placement="top">
                <button class="act danger" @click="removeSession(s)">🗑</button>
              </el-tooltip>
            </span>
          </div>
        </div>
        <el-empty v-if="!sessions.length" description="暂无会话" :image-size="50" />
      </el-scrollbar>
    </aside>

    <!-- 移动端会话抽屉遮罩 -->
    <div v-if="sidebarOpen" class="sidebar-mask" @click="sidebarOpen = false"></div>

    <!-- 主对话区 -->
    <main class="main">
      <header class="chat-header">
        <button class="mobile-menu-btn" @click="sidebarOpen = true">☰</button>
        <div class="chat-title" :title="currentSession?.title">{{ currentSession?.title || '新对话' }}</div>
        <div class="header-controls">
          <el-select
            v-model="params.apiKeyId"
            class="key-select"
            placeholder="选择 API Key"
            filterable
          >
            <el-option v-for="k in keys" :key="k.id" :label="k.remark || `Key #${k.id}`" :value="k.id" />
          </el-select>

          <el-select
            v-model="params.modelName"
            class="model-select"
            placeholder="选择模型"
            filterable
            allow-create
            default-first-option
          >
            <el-option v-for="m in models" :key="m" :label="m" :value="m" />
          </el-select>

          <el-popover placement="bottom-end" :width="300" trigger="click">
            <template #reference>
              <button class="param-btn" title="参数设置">⚙ 参数</button>
            </template>
            <div class="param-pop">
              <div class="param-row">
                <span>Temperature</span>
                <el-slider v-model="params.temperature" :min="0" :max="2" :step="0.1" :show-tooltip="false" />
                <span class="param-val">{{ params.temperature }}</span>
              </div>
              <div class="param-row">
                <span>Max Tokens</span>
                <el-input-number v-model="params.maxTokens" :min="1" :max="32768" :step="256" size="small" />
              </div>
            </div>
          </el-popover>
        </div>
      </header>

      <div class="messages" ref="scrollEl" @scroll="onScroll">
        <div v-if="!messages.length && !streaming" class="welcome">
          <div class="welcome-logo">L</div>
          <h2>你好，我是 Local LLM Guard</h2>
          <p>基于本地大模型，开启你的对话。可以试试下面的问题：</p>
          <div class="suggestions">
            <div v-for="s in suggestions" :key="s" class="suggest" @click="quickSend(s)">{{ s }}</div>
          </div>
        </div>

        <template v-else>
          <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
            <div class="avatar" :class="m.role">{{ m.role === 'user' ? '我' : 'AI' }}</div>
            <div class="msg-body">
              <div class="msg-role">
                {{ m.role === 'user' ? '你' : 'Local LLM Guard' }}
                <span v-if="m.role === 'assistant' && m.completionTokens" class="gen-meta">
                  {{ m.completionTokens }} tokens<span v-if="Number(m.speed || 0) > 0"> · {{ Number(m.speed).toFixed(1) }} tok/s</span>
                </span>
              </div>
              <div v-if="m.content" class="markdown-body" v-html="renderMarkdown(m.content)"></div>
              <div v-else-if="i === messages.length - 1 && streaming" class="typing"><span></span><span></span><span></span></div>
            </div>
          </div>
        </template>
      </div>

      <div class="composer-wrap">
        <div class="composer">
          <el-input
            v-model="input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
            class="composer-input"
            @keydown.enter.exact.prevent="send"
          />
          <button v-if="streaming" class="send-btn stop" title="停止生成" @click="stop">■</button>
          <button v-else class="send-btn" :disabled="!input.trim()" title="发送" @click="send">➤</button>
        </div>
        <div class="composer-hint">内容由本地大模型生成，请注意甄别</div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import request from '../api'

const models = ref([])
const keys = ref([])
const sessions = ref([])
const currentSession = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const scrollEl = ref(null)
const sidebarOpen = ref(false)
let abortController = null
let userScrolledUp = false

const params = reactive({ apiKeyId: null, modelName: '', temperature: 0.7, maxTokens: 2048 })
const suggestions = [
  '帮我写一段 Python 冒泡排序',
  '用一句话解释什么是大语言模型',
  '给我一个三天的上海旅行计划',
  '写一首关于秋天的短诗'
]

function renderMarkdown(text) {
  if (!text) return ''
  return DOMPurify.sanitize(marked.parse(text || ''))
}

async function loadModels() {
  const r = await request.get('/api/admin/chat/getModels')
  models.value = r.data || []
  if (models.value.length && !params.modelName) {
    params.modelName = models.value[0]
  }
}

async function loadKeys() {
  const r = await request.get('/api/admin/chat/getKeys')
  keys.value = r.data || []
  if (keys.value.length && !params.apiKeyId) {
    params.apiKeyId = keys.value[0].id
  }
}

async function loadConfig() {
  const r = await request.get('/api/admin/config')
  const c = r.data || {}
  params.temperature = c.temperature ?? 0.7
  params.maxTokens = c.maxTokens ?? 2048
  if (c.defaultModel && !params.modelName) {
    params.modelName = c.defaultModel
  }
}

async function loadSessions() {
  const r = await request.get('/api/admin/chat/session/list')
  sessions.value = r.data || []
}

async function selectSession(s) {
  currentSession.value = s
  params.modelName = s.modelName || params.modelName
  params.temperature = s.temperature ?? params.temperature
  params.maxTokens = s.maxTokens ?? params.maxTokens
  if (s.apiKeyId != null) {
    params.apiKeyId = s.apiKeyId
  }
  sidebarOpen.value = false
  const r = await request.get(`/api/admin/chat/session/${s.id}/messages`)
  messages.value = r.data || []
  scrollToBottom(true)
}

async function newSession() {
  if (!params.apiKeyId) {
    ElMessage.warning('请先选择 API Key')
    return
  }
  if (!params.modelName) {
    ElMessage.warning('请先在右上角选择模型')
    return
  }
  const r = await request.post('/api/admin/chat/session/create', {
    title: '新对话',
    apiKeyId: params.apiKeyId,
    modelName: params.modelName,
    temperature: params.temperature,
    maxTokens: params.maxTokens
  })
  currentSession.value = { id: r.data, title: '新对话', apiKeyId: params.apiKeyId, modelName: params.modelName }
  messages.value = []
  sidebarOpen.value = false
  await loadSessions()
}

async function ensureSession() {
  if (!currentSession.value) {
    await newSession()
  }
  return currentSession.value
}

async function quickSend(text) {
  input.value = text
  await send()
}

async function send() {
  const content = input.value.trim()
  if (!content || streaming.value) return
  const session = await ensureSession()
  if (!session) return

  input.value = ''
  messages.value.push({ role: 'user', content })
  const assistant = { role: 'assistant', content: '', completionTokens: 0, speed: 0 }
  messages.value.push(assistant)
  streaming.value = true
  userScrolledUp = false
  scrollToBottom(true)

  abortController = new AbortController()
  try {
    const response = await fetch('/api/admin/chat/send', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('admin_token')}`
      },
      signal: abortController.signal,
      body: JSON.stringify({
        sessionId: session.id,
        content,
        apiKeyId: params.apiKeyId,
        modelName: params.modelName,
        temperature: params.temperature,
        maxTokens: params.maxTokens
      })
    })

    if (!response.ok) {
      throw new Error('请求失败: ' + response.status)
    }

    const contentType = response.headers.get('content-type') || ''
    if (contentType.includes('application/json')) {
      const data = await response.json()
      throw new Error(data.message || '请求失败')
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const rawEvent = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        for (const line of rawEvent.split('\n')) {
          if (line.startsWith('data:')) {
            handleStreamData(line.slice(5).trim(), assistant)
          }
        }
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      ElMessage.error(e.message || '发送失败')
    }
    if (!assistant.content) {
      messages.value.pop()
    }
  } finally {
    streaming.value = false
    abortController = null
    if (assistant.content) {
      scrollToBottom(true)
    }
    loadSessions()
  }
}

function stop() {
  if (abortController) {
    abortController.abort()
  }
  streaming.value = false
}

function handleStreamData(data, assistant) {
  if (!data || data === '[DONE]') return
  try {
    const json = JSON.parse(data)
    if (json.error) {
      ElMessage.error(json.error.message || '上游错误')
      return
    }
    if (json.type === 'meta') {
      assistant.completionTokens = Number(json.completionTokens) || 0
      const base = assistant.firstTokenTime || Date.now()
      const elapsed = (Date.now() - base) / 1000
      assistant.speed = elapsed > 0 ? assistant.completionTokens / elapsed : 0
      return
    }
    const choices = json.choices
    if (choices && choices.length) {
      const delta = choices[0].delta
      if (delta && typeof delta.content === 'string') {
        if (!assistant.firstTokenTime) {
          assistant.firstTokenTime = Date.now()
        }
        assistant.content += delta.content
        maybeScroll()
      }
    }
  } catch (e) {
    // ignore malformed chunk
  }
}

function onScroll() {
  const el = scrollEl.value
  if (!el) return
  userScrolledUp = el.scrollHeight - el.scrollTop - el.clientHeight > 80
}

function maybeScroll() {
  if (!userScrolledUp) {
    scrollToBottom(true)
  }
}

function scrollToBottom(force) {
  if (!force && userScrolledUp) return
  nextTick(() => {
    const el = scrollEl.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function shortTime(time) {
  if (!time) return ''
  return time.slice(5, 16)
}

async function renameSession(s) {
  const { value } = await ElMessageBox.prompt('请输入新标题', '重命名', {
    inputValue: s.title,
    confirmButtonText: '确定',
    cancelButtonText: '取消'
  })
  if (value && value.trim()) {
    await request.put('/api/admin/chat/session/update-title', { id: s.id, title: value.trim() })
    s.title = value.trim()
    if (currentSession.value && currentSession.value.id === s.id) {
      currentSession.value.title = value.trim()
    }
  }
}

async function removeSession(s) {
  await ElMessageBox.confirm('删除会话将同时删除其全部消息，确定？', '提示', { type: 'warning' })
  await request.delete(`/api/admin/chat/session/${s.id}`)
  sessions.value = sessions.value.filter((x) => x.id !== s.id)
  if (currentSession.value && currentSession.value.id === s.id) {
    currentSession.value = null
    messages.value = []
  }
}

onMounted(async () => {
  await Promise.all([loadModels(), loadConfig(), loadKeys(), loadSessions()])
})

onBeforeUnmount(() => {
  if (abortController) abortController.abort()
})
</script>

<style scoped>
.chat-app {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
}

/* ---------- 侧边栏 ---------- */
.sidebar {
  width: 264px;
  flex-shrink: 0;
  background: #f8fafc;
  border-right: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  padding: 16px 12px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  padding: 0 6px 14px;
}
.brand-mark {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.new-btn {
  width: 100%;
  margin-bottom: 18px;
  border-radius: 10px;
}
.sidebar-label {
  font-size: 12px;
  color: #94a3b8;
  padding: 0 6px 8px;
}
.session-scroll {
  flex: 1;
  min-height: 0;
}
.session-item {
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.15s;
}
.session-item:hover {
  background: #eef2f7;
}
.session-item.active {
  background: #eef2ff;
}
.session-title {
  font-size: 14px;
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.session-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
}
.session-time {
  font-size: 11px;
  color: #cbd5e1;
}
.session-actions {
  display: none;
  gap: 2px;
}
.session-item:hover .session-actions {
  display: flex;
}
.act {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: #64748b;
  padding: 2px 4px;
  border-radius: 4px;
}
.act:hover {
  background: #e2e8f0;
}
.act.danger:hover {
  color: #ef4444;
}

/* ---------- 主区 ---------- */
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
}
.chat-header {
  height: 60px;
  flex-shrink: 0;
  border-bottom: 1px solid #eef2f7;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}
.chat-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.header-controls {
  display: flex;
  align-items: center;
  gap: 10px;
}
.key-select {
  width: 170px;
}
.model-select {
  width: 220px;
}
.param-btn {
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #475569;
  border-radius: 8px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 13px;
}
.param-btn:hover {
  background: #f8fafc;
}
.param-pop {
  padding: 4px;
}
.param-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  font-size: 13px;
  color: #334155;
}
.param-row .el-slider {
  flex: 1;
}
.param-val {
  width: 34px;
  text-align: right;
  color: #6366f1;
  font-weight: 600;
}

/* ---------- 消息区 ---------- */
.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 0;
  background: #fff;
}
.welcome {
  max-width: 720px;
  margin: 8vh auto 0;
  text-align: center;
  padding: 0 24px;
}
.welcome-logo {
  width: 64px;
  height: 64px;
  border-radius: 20px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  font-size: 30px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}
.welcome h2 {
  margin: 0 0 10px;
  color: #0f172a;
}
.welcome p {
  color: #64748b;
  margin: 0 0 24px;
}
.suggestions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  text-align: left;
}
.suggest {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 12px 14px;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
  transition: all 0.15s;
}
.suggest:hover {
  border-color: #6366f1;
  color: #6366f1;
  background: #f8faff;
}

.msg {
  display: flex;
  gap: 14px;
  max-width: 860px;
  margin: 0 auto;
  padding: 14px 24px;
}
.msg.assistant {
  background: #f8fafc;
}
.avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}
.avatar.user {
  background: #e0e7ff;
  color: #4f46e5;
}
.avatar.assistant {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
}
.msg-body {
  min-width: 0;
  flex: 1;
}
.msg-role {
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 6px;
}
.gen-meta {
  margin-left: 10px;
  font-size: 12px;
  color: #6366f1;
}

/* Markdown */
.markdown-body {
  font-size: 14.5px;
  line-height: 1.75;
  color: #1e293b;
  word-break: break-word;
}
.markdown-body :deep(p) {
  margin: 0 0 10px;
}
.markdown-body :deep(pre) {
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 10px;
  padding: 14px 16px;
  overflow-x: auto;
  margin: 10px 0;
}
.markdown-body :deep(code) {
  background: #f1f5f9;
  border-radius: 4px;
  padding: 2px 6px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
}
.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
  color: #e2e8f0;
}
.markdown-body :deep(ul), .markdown-body :deep(ol) {
  padding-left: 22px;
  margin: 8px 0;
}
.markdown-body :deep(blockquote) {
  border-left: 3px solid #6366f1;
  margin: 10px 0;
  padding: 4px 14px;
  color: #64748b;
  background: #f8fafc;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 10px 0;
}
.markdown-body :deep(th), .markdown-body :deep(td) {
  border: 1px solid #e2e8f0;
  padding: 6px 12px;
}

.typing {
  display: inline-flex;
  gap: 5px;
  padding: 8px 0;
}
.typing span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #c7d2fe;
  animation: blink 1.2s infinite;
}
.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink {
  0%, 60%, 100% { opacity: 0.3; transform: translateY(0); }
  30% { opacity: 1; transform: translateY(-3px); }
}

/* ---------- 输入区 ---------- */
.composer-wrap {
  flex-shrink: 0;
  padding: 0 24px 16px;
  background: #fff;
}
.composer {
  max-width: 860px;
  margin: 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  padding: 12px 14px;
  background: #f8fafc;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.composer:focus-within {
  border-color: #6366f1;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.1);
  background: #fff;
}
.composer-input {
  flex: 1;
}
.composer-input :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  background: transparent;
  padding: 2px 0;
  font-size: 14px;
  line-height: 1.6;
  resize: none;
}
.send-btn {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  border: none;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  font-size: 16px;
  cursor: pointer;
  flex-shrink: 0;
  transition: opacity 0.15s;
}
.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.send-btn.stop {
  background: #ef4444;
}
.composer-hint {
  max-width: 860px;
  margin: 8px auto 0;
  text-align: center;
  font-size: 12px;
  color: #cbd5e1;
}

/* ---------- 移动端适配 ---------- */
.mobile-menu-btn {
  display: none;
}
.sidebar-mask {
  display: none;
}
@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 100;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
  }
  .sidebar.open {
    transform: translateX(0);
  }
  .sidebar-mask {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.4);
    z-index: 99;
  }
  .mobile-menu-btn {
    display: inline-flex;
    border: none;
    background: #f1f5f9;
    border-radius: 8px;
    width: 34px;
    height: 34px;
    font-size: 16px;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    flex-shrink: 0;
  }
  .chat-header {
    position: sticky;
    top: 0;
    z-index: 10;
    background: #fff;
  }
  .header-controls .key-select,
  .header-controls .model-select {
    display: none;
  }
  .msg {
    max-width: 100%;
    padding: 12px 14px;
  }
  .avatar {
    width: 30px;
    height: 30px;
    font-size: 12px;
  }
  .composer-wrap {
    padding: 0 12px 10px;
  }
  .composer-hint {
    display: none;
  }
  /* 键盘弹出时调整 */
  .param-btn {
    padding: 5px 8px;
  }
}
</style>
