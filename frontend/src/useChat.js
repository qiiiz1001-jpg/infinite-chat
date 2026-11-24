import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api, currentUser, id, parseWire, post, state } from './api'

const reminderKey = 'reminders'
const reminderPrefix = '[助手提醒] '
const wireErrors = {
  INVALID_MESSAGE:'消息格式有误。',
  SESSION_ACCESS_DENIED:'当前会话无法发送此消息。',
  REFERENCE_ACCESS_DENIED:'参考会话已经不可访问。',
  MESSAGE_DISPATCH_FAILED:'消息未完全投递，请刷新历史核对。'
}
function stamp(value) {
  if (typeof value === 'number' || /^\d+$/.test(value || '')) return Number(value)
  return Date.parse(value) || 0
}
function normalizeSession(session) {
  return { ...session, type:Number(session.type), sessionId:id(session.sessionId), receiverId:session.receiverId == null ? null : id(session.receiverId) }
}
function receiveText(message) { return String(message.body?.content ?? message.content ?? '') }

export function useChat() {
  const sessions = ref([])
  const filtered = ref(null)
  const unread = reactive(new Map())
  const pending = reactive(new Map())
  const messages = reactive(new Map())
  const reminders = reactive(new Map())
  const selected = ref('')
  const lastRoom = ref('')
  const status = ref('')
  const isError = ref(false)
  const connection = ref('正在连接')
  const connected = ref(false)
  const hasOlder = ref(false)
  const historyBusy = ref(false)
  const taskRevision = ref(0)
  const summary = ref('')
  const session = computed(() => sessions.value.find(item => item.sessionId === selected.value))
  const bot = computed(() => sessions.value.find(item => item.type === 2))
  const showingReminders = computed(() => selected.value === reminderKey)
  const direct = computed(() => sessions.value.filter(item => item.type !== 2))
  const visible = computed(() => (filtered.value ?? direct.value).filter(item => item.type !== 2))
  const thread = computed(() => {
    const values = [...(showingReminders.value ? reminders : messages).values()]
    const delivered = values.sort((a,b) => stamp(a.createdTime)-stamp(b.createdTime)
      || a.messageId.length-b.messageId.length || a.messageId.localeCompare(b.messageId))
    if (showingReminders.value) return delivered
    return [...delivered, ...[...pending.values()].filter(entry => entry.sessionId === selected.value)]
  })
  let socket, heartbeat, retry, cursor
  let cycle = 0, historyVersion = 0, searchVersion = 0, missedAt = 0, lastReply = 0, failures = 0
  let disposed = false

  function feedback(text='', error=false) { status.value=text; isError.value=error }
  function isReminder(message) {
    return id(message.sessionId) === bot.value?.sessionId && id(message.senderId) === bot.value.receiverId
      && receiveText(message).startsWith(reminderPrefix)
  }
  function storeMessage(message) {
    const key = id(message.messageId)
    if (!key || isReminder(message)) return
    messages.set(key, { ...message, messageId:key })
  }

  async function loadSessions() {
    sessions.value = (await api('/api/user/agent/sessions') || []).map(normalizeSession)
    filtered.value = null
  }
  async function search(keyword) {
    const version = ++searchVersion
    if (!keyword.trim()) { filtered.value = null; return }
    const list = await api('/api/user/agent/sessions?' + new URLSearchParams({keyword:keyword.trim()}))
    if (version !== searchVersion) return
    filtered.value = (list || []).map(normalizeSession)
    const byId = new Map(sessions.value.map(item => [item.sessionId, item]))
    for (const row of filtered.value) byId.set(row.sessionId,row)
    sessions.value = [...byId.values()]
  }
  async function open(target) {
    const next = id(target)
    if (next === selected.value) return
    ++historyVersion
    selected.value = next
    feedback()
    summary.value = ''
    messages.clear()
    cursor = null
    hasOlder.value = false
    historyBusy.value = false
    unread.delete(next)
    if (session.value?.type !== 2 && next && !showingReminders.value) lastRoom.value = next
    if (next) await refresh()
  }
  async function openMessages() {
    const room = direct.value.find(item => item.sessionId === lastRoom.value) || direct.value[0]
    await open(room?.sessionId || '')
  }
  async function openAssistant() {
    if (!bot.value) await loadSessions()
    await open(bot.value?.sessionId || '')
  }
  async function refresh(older=false) {
    if (!selected.value || historyBusy.value || (older && !hasOlder.value)) return
    const target = selected.value
    const version = historyVersion
    historyBusy.value = true
    try {
      if (showingReminders.value) {
        const list = await api('/api/user/agent/reminders')
        if (version !== historyVersion) return
        for (const row of list || []) {
          const key = id(row.messageId)
          reminders.set(key, { messageId:key, createdTime:row.messageTime, content:row.content, reminder:true })
        }
        return
      }
      const body = { sessionId:target, beforeTime:older ? cursor.time : Date.now(), limit:30 }
      if (older) body.beforeMessageId = cursor.message
      const list = await post('/api/message/history', body) || []
      if (version !== historyVersion) return
      for (const row of list) storeMessage(row)
      const oldest = list.at(-1)
      if (older || !cursor) {
        if (oldest) cursor = { time:stamp(oldest.createdTime), message:id(oldest.messageId) }
        hasOlder.value = list.length === 30 && !!cursor
      }
    } catch (err) { if (version === historyVersion) feedback(err.message,true) }
    finally { if (version === historyVersion) historyBusy.value = false }
  }
  async function summarize() {
    const room = selected.value
    try {
      const text = await post('/api/message/summary',{sessionId:room,hours:24})
      if (room === selected.value) summary.value = text
    } catch (err) { if (room === selected.value) feedback(err.message,true) }
  }

  function handle(message) {
    if (message.error) {
      const item = pending.get(message.clientMessageId)
      const reason = wireErrors[message.error] || '发送失败。'
      if (item) { clearTimeout(item.timer); item.warning = reason }
      if (!item || item.sessionId === selected.value) feedback(reason,true)
      return
    }
    const room = id(message.sessionId)
    const key = id(message.messageId)
    if (!room || !key) return
    if (pending.has(message.clientMessageId)) {
      clearTimeout(pending.get(message.clientMessageId).timer)
      pending.delete(message.clientMessageId)
    }
    if (isReminder(message)) {
      const known = reminders.has(key)
      reminders.set(key,{ messageId:key, createdTime:message.createdTime, content:receiveText(message).slice(reminderPrefix.length), reminder:true })
      if (!showingReminders.value && !known) unread.set(reminderKey,(unread.get(reminderKey)||0)+1)
      taskRevision.value++
      return
    }
    if (room === bot.value?.sessionId && id(message.senderId) === bot.value.receiverId) taskRevision.value++
    if (room === selected.value) storeMessage(message)
    else if (id(message.senderId) !== currentUser()) unread.set(room,(unread.get(room)||0)+1)
  }
  async function recover(since) {
    if (!since || since >= Date.now()) return
    const result = await post('/api/message/offline',{userId:currentUser(),offlineTime:since})
    for (const rows of Object.values(result || {})) for (const row of rows || []) handle(row)
  }
  function reconnect() {
    if (disposed) return
    connected.value = false
    connection.value = '已断开，正在重连'
    clearTimeout(retry)
    retry = setTimeout(() => connect(true),Math.min(20000,1200*2**failures++))
  }
  async function connect(retrying=false) {
    if (disposed || !state.user || socket?.readyState <= WebSocket.OPEN) return
    const user = state.user
    const active = ++cycle
    connection.value = retrying ? '正在重新连接' : '正在连接'
    try {
      const uri = retrying || !user.nettyUri ? await api('/api/user/refresh/uri') : user.nettyUri
      if (active !== cycle || disposed) return
      const client = new WebSocket(uri,['im','bearer.'+user.accessToken])
      socket = client
      client.onopen = () => {
        if (active !== cycle) return
        failures = 0
        connected.value = true
        connection.value = '实时消息已连接'
        lastReply = Date.now()
        const since = missedAt || Number(user.offlineTime)
        missedAt = 0
        recover(since).catch(err => { if (!disposed) feedback(err.message,true) })
        clearInterval(heartbeat)
        heartbeat = setInterval(() => {
          if (client.readyState !== WebSocket.OPEN) return
          if (Date.now()-lastReply > 65000) client.close()
          else client.send('ping')
        },25000)
      }
      client.onmessage = event => {
        if (active !== cycle) return
        lastReply = Date.now()
        if (event.data === 'pong') return
        try { handle(parseWire(event.data)) }
        catch { feedback('收到无法读取的消息。',true) }
      }
      client.onerror = () => { if (active === cycle) connection.value = '连接异常，等待重连' }
      client.onclose = () => {
        if (active !== cycle) return
        socket = null
        clearInterval(heartbeat)
        missedAt ||= Date.now()
        reconnect()
      }
    } catch { if (active === cycle) reconnect() }
  }
  function send(text, reference) {
    const room = session.value
    if (!room || !text.trim()) return false
    if (socket?.readyState !== WebSocket.OPEN) { feedback('实时连接尚未建立。',true); return false }
    if (room.type !== 1 && !room.receiverId) { feedback('未找到会话接收者。',true); return false }
    const clientMessageId = crypto.randomUUID()
    socket.send(JSON.stringify({
      sessionId:room.sessionId, sessionType:room.type, receiverId:room.receiverId,
      ...(room.type === 2 && reference ? {referenceSessionId:reference} : {}),
      type:0, body:{content:text.trim()}, clientMessageId
    }))
    const timer = setTimeout(() => {
      const item = pending.get(clientMessageId)
      if (item) item.warning = '暂未收到确认，可刷新会话核对'
    },10000)
    pending.set(clientMessageId,{
      messageId:clientMessageId, senderId:currentUser(), sessionId:room.sessionId, content:text.trim(),
      pending:true, warning:'发送中', timer
    })
    feedback()
    return true
  }
  const online = () => connect(true)
  onMounted(() => window.addEventListener('online',online))
  onUnmounted(() => {
    disposed = true
    ++cycle
    window.removeEventListener('online',online)
    clearInterval(heartbeat)
    clearTimeout(retry)
    socket?.close()
    for (const item of pending.values()) clearTimeout(item.timer)
  })
  return { sessions, visible, direct, selected, session, bot, showingReminders, reminderKey, unread, thread, connection,
    connected, hasOlder, historyBusy, taskRevision, summary, status, isError, feedback, loadSessions, search, open,
    openMessages, openAssistant, refresh, summarize, connect, send }
}

