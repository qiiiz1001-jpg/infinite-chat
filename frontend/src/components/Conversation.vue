<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowLeft, ArrowUp, BellRing, Bot, Gift, MessageCircle, RefreshCw, TextQuote, UsersRound } from '@lucide/vue'
import { currentUser, id, localDate, parseWire } from '../api'

const props = defineProps({ chat:Object, assistant:Boolean })
const emit = defineEmits(['back','members','packet','packetDetails','createReminder'])
const { session, direct, thread, selected, showingReminders, connected, connection, summary, historyBusy, hasOlder, status, isError } = props.chat
const draft = ref('')
const reference = ref('')
const scroll = ref(null)
const composer = ref(null)
const title = computed(() => showingReminders.value ? '提醒' : props.assistant ? 'AI 助手' : session.value?.name || '消息')
const ready = computed(() => props.assistant ? session.value?.type === 2 : session.value && session.value.type !== 2)

async function toBottom() {
  await nextTick()
  if (scroll.value) scroll.value.scrollTop=scroll.value.scrollHeight
}
watch(selected, () => { draft.value=''; reference.value=''; toBottom() })
watch(() => thread.value.at(-1)?.messageId, toBottom)
async function loadOlder() {
  const pane = scroll.value
  const previous = pane?.scrollTop || 0
  const height = pane?.scrollHeight || 0
  await props.chat.refresh(true)
  await nextTick()
  if (pane) pane.scrollTop = previous + pane.scrollHeight - height
}
function mine(message) { return id(message.senderId) === currentUser() }
function send() {
  if (ready.value && props.chat.send(draft.value,reference.value)) {
    draft.value = ''
    nextTick(() => composer.value?.focus())
  }
}
function enter(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  send()
}
function packet(message) {
  const raw = message.body?.content ?? message.content
  if (raw && typeof raw !== 'string') return raw
  try { return parseWire(raw) } catch { return null }
}
function openPacket(message) {
  const data = packet(message)
  if (data?.redPacketId) emit('packetDetails',data.redPacketId,mine(message))
}
function reminderDraft() {
  draft.value = '提醒我'
  nextTick(() => composer.value?.focus())
}
defineExpose({ reminderDraft })
</script>

<template>
  <section class="conversation" :aria-label="title">
    <header class="conversation-header">
      <button class="icon-button mobile-back" aria-label="返回会话列表" @click="emit('back')"><ArrowLeft :size="19" /></button>
      <span class="avatar room-avatar" :class="{ bot: assistant, bell: showingReminders }">
        <Bot v-if="assistant" :size="21" /><BellRing v-else-if="showingReminders" :size="19" />
        <UsersRound v-else-if="session?.type === 1" :size="19" />
        <span v-else>{{ session?.name?.slice(0,1) || '聊' }}</span>
      </span>
      <div class="conversation-title">
        <h1>{{ title }}</h1>
        <span v-if="!showingReminders" class="connection"><i :class="{ online: connected }"></i>{{ connection }}</span>
        <span v-else class="muted">定时提醒</span>
      </div>
      <div v-if="ready || showingReminders" class="header-actions">
        <button class="icon-button" aria-label="刷新消息" title="刷新消息" :disabled="historyBusy" @click="props.chat.refresh()"><RefreshCw :size="17"/></button>
        <button v-if="ready && !assistant" class="quiet tool-action" @click="props.chat.summarize()"><TextQuote :size="17"/> 总结</button>
        <button v-if="ready && !assistant && session.type===1" class="icon-button" title="群成员" aria-label="群成员" @click="emit('members',session)"><UsersRound :size="18"/></button>
        <button v-if="ready && !assistant" class="icon-button" title="发红包" aria-label="发红包" @click="emit('packet',session)"><Gift :size="18"/></button>
      </div>
    </header>
    <div ref="scroll" class="thread" role="log" aria-label="会话消息">
      <button v-if="hasOlder && !showingReminders && ready" class="older quiet" :disabled="historyBusy" @click="loadOlder">加载更早的消息</button>
      <div v-if="!thread.length" class="thread-empty">
        <component :is="showingReminders ? BellRing : assistant ? Bot : MessageCircle" :size="27"/>
        <strong>{{ historyBusy ? '正在读取消息…' : showingReminders ? '暂无提醒' : assistant ? '有事交给助手' : ready ? '暂无消息' : '选择一个会话' }}</strong>
        <p v-if="assistant && ready && !historyBusy">可以拟写消息、邮件，或安排个人提醒。</p>
      </div>
      <article v-for="message in thread" :key="message.messageId" class="thread-message" :class="{ mine:mine(message) }">
        <span v-if="!mine(message)" class="message-avatar">
          <BellRing v-if="message.reminder" :size="15"/><Bot v-else-if="assistant" :size="15"/><span v-else>{{ (message.nickname || session?.name || '聊').slice(0,1) }}</span>
        </span>
        <div class="message-content">
          <header>{{ message.reminder ? '提醒' : mine(message) ? '我' : message.nickname || (assistant ? '轻聊助手' : session?.name || '会话成员') }}
            <time>{{ message.warning || localDate(message.createdTime) }}</time>
          </header>
          <button v-if="Number(message.type) === 3" class="packet-bubble" :disabled="!packet(message)?.redPacketId" @click="openPacket(message)">
            <Gift :size="19"/><span>{{ packet(message)?.redPacketWrapperText || '红包' }}<small>查看红包</small></span>
          </button>
          <div v-else class="bubble">{{ message.body?.content ?? message.content ?? '消息内容不可用' }}</div>
        </div>
      </article>
    </div>
    <section v-if="summary" class="summary-box">
      <header><strong>过去 24 小时的会话总结</strong><button class="quiet" @click="summary=''">收起</button></header>
      <p>{{ summary }}</p>
    </section>
    <div v-if="ready" class="composer-area">
      <form class="composer" @submit.prevent="send">
        <label v-if="assistant" class="reference">参考会话
          <select v-model="reference" aria-label="参考会话"><option value="">不参考其他会话</option>
            <option v-for="room in direct" :key="room.sessionId" :value="room.sessionId">{{ room.name }}{{ room.type===1 ? ' · 群聊' : '' }}</option>
          </select>
        </label>
        <div class="compose-input">
          <textarea ref="composer" v-model="draft" :placeholder="assistant ? '例如：给演示用户 B 发「下午三点开会」' : '输入消息'" aria-label="输入消息" maxlength="4096" rows="2" @keydown="enter"></textarea>
          <button class="primary" aria-label="发送" title="发送" :disabled="!connected || !draft.trim()"><ArrowUp :size="21"/></button>
        </div>
      </form>
    </div>
    <footer v-if="showingReminders" class="reminder-footer"><span>这里接收已经到期的提醒。</span><button class="outline" @click="emit('createReminder')">设置提醒</button></footer>
    <p v-if="status" class="conversation-status" :class="{error:isError}" role="status">{{ status }}</p>
  </section>
</template>

