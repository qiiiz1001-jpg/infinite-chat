<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { BellRing, Clock3, Mail, MessageSquare, RefreshCw } from '@lucide/vue'
import { api, dateInput, encode, id, localDate, post } from '../api'
import { useOperation } from '../useOperation'
import ModalPanel from './ModalPanel.vue'

const props=defineProps({ sessions:Array, revision:Number })
const base='/api/user/agent/actions'
const items=ref([])
const modal=ref(null)
const mode=ref('')
const active=ref(null)
const body=ref('')
const time=ref('')
const history=ref(false)
const {busy,message,failed,feedback,run}=useOperation()
let timer, revision=0
const states={
  DRAFT:'待确认', SCHEDULED:'已排期', PENDING:'待执行', RUNNING:'处理中',
  DISPATCHING:'正在投递', SENT:'已投递', CANCELLED:'已取消', CANCELED:'已取消', FAILED:'失败'
}
const draft = computed(()=>items.value.filter(t=>t.status==='DRAFT'))
const upcoming = computed(()=>items.value.filter(t=>['PENDING','SCHEDULED','RUNNING','DISPATCHING'].includes(t.status)))
const finished = computed(()=>items.value.filter(t=>['SENT','FAILED','CANCELLED','CANCELED'].includes(t.status)))
const groups=computed(()=>[
  {label:'待确认',items:draft.value},
  {label:'已排期与执行中',items:upcoming.value},
  {label:'执行记录',items:history.value ? finished.value : []}
])
const itemId = task => encode(task.actionId)
function status(task) {
  return task.status==='PENDING' && Number(task.executeAt)>Date.now() ? '已排期' : states[task.status] || task.status
}
function detail(task) {
  if (task.kind==='EMAIL') {
    const [target, subject, ...lines]=task.content.split('\n')
    return { kind:'发送邮件',target,subject,text:lines.join('\n'),icon:Mail }
  }
  if (task.kind==='REMINDER') return { kind:'个人提醒', target:'提醒', text:task.content, icon:BellRing }
  return { kind:'发送消息', target:props.sessions.find(room=>id(room.sessionId)===id(task.sessionId))?.name||'会话', text:task.content, icon:MessageSquare }
}
const selected = computed(()=>active.value ? detail(active.value) : null)
function when(task) {
  return task.executeAt ? localDate(task.executeAt) : task.messageTime ? localDate(task.messageTime) : '确认后立即执行'
}
async function reload() {
  const request=++revision
  const list=await api(base)
  if(revision===request) items.value=list||[]
}
onMounted(() => {
  run(reload)
  timer=setInterval(()=>{if(!document.hidden && !busy.value && !active.value)reload().catch(()=>{})},10000)
})
onUnmounted(()=>clearInterval(timer))
watch(()=>props.revision,()=>reload().catch(()=>{}))
function show(task,next) {
  active.value=task
  mode.value=next
  body.value=task.content
  time.value=dateInput(task.executeAt)
  feedback()
  modal.value.open()
}
function close() { modal.value.close(); active.value=null }
function complete() {
  return run(async()=>{
    const task=active.value
    const url=base+'/'+itemId(task)
    if (mode.value==='confirm') await post(url+'/confirm',{version:task.version})
    else await api(url,{method:'PATCH',body:{version:task.version,content:body.value.trim(),executeAt:time.value ? Date.parse(time.value) : null}})
    const confirmation=mode.value==='confirm'
    close()
    await reload()
    feedback(confirmation?'任务已确认。':'任务已修改，请重新核对。')
  })
}
function decide(task,next) {
  if (!confirm(next==='cancel'?'确定取消此任务？':'确认重试此任务？')) return
  return run(async()=>{
    await post(base+'/'+itemId(task)+'/'+next,{version:task.version})
    await reload()
  })
}
</script>

<template>
  <section class="tasks surface">
    <header><div class="tasks-title"><Clock3 :size="19"/><h2>定时任务与发送确认</h2></div>
      <button class="icon-button" aria-label="刷新任务" title="刷新任务" :disabled="busy" @click="run(reload)"><RefreshCw :size="17"/></button></header>
    <div class="task-scroll">
      <p v-if="!draft.length && !upcoming.length" class="section-empty">暂无待确认或排期任务。</p>
      <template v-for="group in groups" :key="group.label">
        <h3 v-if="group.items.length">{{ group.label }}<span>{{ group.items.length }}</span></h3>
        <article v-for="task in group.items" :key="task.actionId" class="task-row">
          <header><component :is="detail(task).icon" :size="16"/><strong>{{ detail(task).kind }}</strong><span class="task-state">{{ status(task) }}</span></header>
          <p class="task-meta">{{ detail(task).target }} · {{ when(task) }}</p>
          <p v-if="detail(task).subject" class="task-text"><strong>{{ detail(task).subject }}</strong></p>
          <p class="task-text">{{ detail(task).text }}</p>
          <p v-if="task.lastError" class="task-meta">{{ task.lastError }}</p>
          <div class="row-actions">
            <button v-if="task.status==='DRAFT'" class="outline" @click="show(task,'confirm')">核对并确认</button>
            <template v-if="['DRAFT','SCHEDULED','PENDING'].includes(task.status)">
              <button v-if="!task.attempts && task.kind!=='EMAIL'" class="quiet" @click="show(task,'edit')">修改</button>
              <button class="quiet muted" :disabled="busy" @click="decide(task,'cancel')">取消</button>
            </template>
            <button v-if="task.status==='FAILED' && task.kind!=='EMAIL'" class="quiet" :disabled="busy" @click="decide(task,'retry')">重试投递</button>
          </div>
        </article>
      </template>
      <button v-if="finished.length" class="quiet history-toggle" :aria-expanded="history" @click="history=!history">{{ history ? '收起执行记录' : '查看执行记录' }}　{{ finished.length }}</button>
      <p v-if="message && !active" class="view-status" :class="{error:failed}" role="status">{{ message }}</p>
    </div>
    <ModalPanel ref="modal" @close="active=null" :title="mode==='confirm'?'确认执行任务':'修改任务'">
      <form v-if="active" class="form-stack" @submit.prevent="complete">
        <template v-if="mode==='confirm'">
          <p><strong>{{ selected.kind }}</strong> · {{ selected.target }}</p>
          <p class="muted">执行时间 {{ when(active) }}</p>
          <div class="confirmation-text"><strong v-if="selected.subject">{{ selected.subject }}</strong><p>{{ selected.text }}</p></div>
        </template>
        <template v-else>
          <label>内容<textarea v-model="body" maxlength="2000" rows="4" required></textarea></label>
          <label>执行时间<input v-model="time" type="datetime-local" :required="active.kind==='REMINDER'"></label>
        </template>
        <p v-if="message && failed" class="error" role="status">{{ message }}</p>
        <div class="inline-row"><button class="primary" :disabled="busy">{{ mode==='confirm'?'确认执行':'保存修改' }}</button>
          <button type="button" class="quiet" @click="close">返回</button></div>
      </form>
    </ModalPanel>
  </section>
</template>

