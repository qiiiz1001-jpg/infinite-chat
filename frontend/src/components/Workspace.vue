<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { BellRing, BookOpen, Bot, ContactRound, LogOut, MessageSquare, MessagesSquare, Plus, Search, UsersRound } from '@lucide/vue'
import { api, identity, state } from '../api'
import { useChat } from '../useChat'
import Contacts from './Contacts.vue'
import Knowledge from './Knowledge.vue'
import Conversation from './Conversation.vue'
import RoomDialogs from './RoomDialogs.vue'
import Tasks from './Tasks.vue'

const chat = useChat()
const { visible, selected, unread, reminderKey, sessions, taskRevision } = chat
const page = ref('chat')
const query = ref('')
const searchError = ref('')
const roomOpen = ref(false)
const dialogs = ref(null)
const conversation = ref(null)
const nav = [
  { key:'chat', label:'消息', icon:MessageSquare },
  { key:'contacts', label:'联系人', icon:ContactRound },
  { key:'knowledge', label:'知识库', icon:BookOpen },
  { key:'tasks', label:'AI 助手', icon:Bot }
]
const identityLabel = computed(() => state.user?.nickname || state.user?.email || '轻聊用户')
const hasUnread = computed(() => [...unread].some(([id,count]) => count && (id===reminderKey || sessions.value.find(row=>row.sessionId===id)?.type!==2)))
let navigation = 0

async function go(next, room) {
  const order = ++navigation
  const previous = page.value
  page.value = next
  if (next === 'chat') roomOpen.value = !!room || previous === 'chat' && roomOpen.value
  try {
    if (next !== 'chat' && next !== 'tasks') return
    if (!sessions.value.length) await chat.loadSessions()
    if (order !== navigation) return
    if (next === 'tasks') await chat.openAssistant()
    else if (room) {
      if (!sessions.value.some(item => item.sessionId === String(room))) await chat.loadSessions()
      if (order === navigation) await chat.open(room)
    } else await chat.openMessages()
  } catch (error) { if (order === navigation) chat.feedback(error.message,true) }
}
async function search() {
  searchError.value = ''
  try { await chat.search(query.value) }
  catch (error) { searchError.value = error.message }
}
function open(room) {
  roomOpen.value = true
  return chat.open(room)
}
async function reminder() {
  await go('tasks')
  await nextTick()
  conversation.value?.reminderDraft()
}
async function roomsChanged(room) {
  try {
    await chat.loadSessions()
    if (page.value === 'chat') await (room ? open(room) : chat.openMessages())
  } catch (err) { chat.feedback(err.message,true) }
}
async function logout() {
  try { await api('/api/user/logout') } catch { /* Exit the local session as well. */ }
  finally { identity(null) }
}
onMounted(async () => {
  await go('chat')
  chat.connect()
})
</script>

<template>
  <div class="workspace">
    <aside class="rail" aria-label="工作区导航">
      <header class="rail-logo" aria-label="轻聊"><MessagesSquare :size="23"/></header>
      <nav>
        <button v-for="item in nav" :key="item.key" :aria-current="page === item.key ? 'page' : undefined" @click="go(item.key)">
          <span class="rail-icon"><component :is="item.icon" :size="21"/><i v-if="item.key==='chat' && hasUnread"></i></span>
          <span>{{ item.label }}</span>
        </button>
      </nav>
      <footer class="rail-user">
        <span class="rail-avatar" :title="identityLabel">{{ identityLabel.slice(0,1) }}</span>
        <button class="rail-logout" aria-label="退出登录" title="退出登录" @click="logout"><LogOut :size="17"/></button>
      </footer>
    </aside>
    <main class="workspace-main">
      <div v-if="page==='chat'" class="chat-layout" :class="{ 'room-open':roomOpen }">
        <aside class="session-rail" aria-label="会话列表">
          <header><h2>消息</h2><button class="outline new-group" @click="dialogs.group()"><Plus :size="16"/> 新建群聊</button></header>
          <form class="session-search" @submit.prevent="search">
            <Search :size="17"/><input v-model="query" aria-label="搜索会话" placeholder="搜索会话" maxlength="100" type="search" @search="search">
          </form>
          <p v-if="searchError" class="error" role="status">{{ searchError }}</p>
          <nav class="session-list">
            <button v-for="room in visible" :key="room.sessionId" :aria-current="selected===room.sessionId" @click="open(room.sessionId)">
              <span class="avatar" :class="{ group:room.type===1 }">
                <UsersRound v-if="room.type===1" :size="18"/><span v-else>{{ room.name?.slice(0,1) || '聊' }}</span>
              </span>
              <span class="session-title"><strong>{{ room.name }}</strong><small>{{ room.type===1 ? '群聊' : '单聊' }}</small></span>
              <span v-if="unread.get(room.sessionId)" class="unread">{{ Math.min(99,unread.get(room.sessionId)) }}</span>
            </button>
            <p v-if="!visible.length && query.trim()" class="list-empty muted">没有找到匹配的会话。</p>
            <button v-if="!query.trim() || '提醒'.includes(query.trim())" class="reminders-link" :aria-current="selected===reminderKey" @click="open(reminderKey)">
              <span class="avatar bell"><BellRing :size="18"/></span><span class="session-title"><strong>提醒</strong><small>查看到期提醒</small></span>
              <span v-if="unread.get(reminderKey)" class="unread">{{ Math.min(99,unread.get(reminderKey)) }}</span>
            </button>
          </nav>
        </aside>
        <Conversation :chat="chat" @back="roomOpen=false" @members="room => dialogs?.members(room)" @packet="room => dialogs?.packet(room)" @packet-details="(id,mine) => dialogs?.packetDetails(id,mine)" @create-reminder="reminder"/>
      </div>
      <Contacts v-else-if="page==='contacts'" @open-room="room => go('chat',room)" />
      <Knowledge v-else-if="page==='knowledge'" :preferred="selected" />
      <div v-else class="assistant-layout">
        <Conversation ref="conversation" :chat="chat" assistant />
        <Tasks :sessions="sessions" :revision="taskRevision" />
      </div>
    </main>
    <RoomDialogs ref="dialogs" @rooms-changed="roomsChanged" @status="chat.feedback"/>
  </div>
</template>

