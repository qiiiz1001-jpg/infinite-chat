<script setup>
import { onMounted, ref } from 'vue'
import { ContactRound, Search, UsersRound } from '@lucide/vue'
import { api, currentUser, encode, id, post } from '../api'
import { useOperation } from '../useOperation'
import ModalPanel from './ModalPanel.vue'

const emit = defineEmits(['openRoom'])
const { busy, message, failed, feedback, run } = useOperation()
const modal = ref(null)
const friends = ref([])
const applications = ref([])
const groups = ref([])
const keyword = ref('')
const found = ref(null)
const searched = ref(false)
const draft = ref('')
const tab = ref('friends')
const pagination = '?pageNum=1&pageSize=100'
const states = ['待处理','已通过','已拒绝','已读','已过期']
const contact = () => '/api/contact/' + encode(currentUser())
async function load() {
  const [links, apps, joined] = await Promise.all([
    api(contact()+'/friend'+pagination),
    api(contact()+'/apply'+pagination),
    api('/api/group/user/'+encode(currentUser())+pagination)
  ])
  friends.value=links?.list||[]
  applications.value=apps?.list||[]
  groups.value=joined?.list||[]
}
onMounted(() => run(load))
function search() {
  return run(async () => {
    const result = await api(contact()+'/user/search?'+new URLSearchParams({keyword:keyword.value.trim()}))
    found.value = result && id(result.userId)!==currentUser() ? result : null
    searched.value = true
  })
}
function open(room) { emit('openRoom',room) }
function apply() { draft.value=''; modal.value.open() }
async function add() {
  await run(async () => {
    await post(contact()+'/friend/'+encode(found.value.userId),{msg:draft.value.trim()})
    modal.value.close()
    await load()
    feedback('好友申请已发送。')
  })
}
function changeFriend(person, operation) {
  const blocked = Number(person.status)===1
  const remove = operation==='friend'
  if ((remove || !blocked) && !confirm(remove ? '确定删除这个好友？' : '拉黑这个联系人？')) return
  return run(async () => {
    await api(contact()+'/'+operation+'/'+encode(person.userId),{method:remove||blocked ? 'DELETE' : 'POST'})
    await load()
  })
}
function decide(request, action) {
  return run(async () => {
    await post(contact()+'/application/'+action,{receiveuserIds:[id(request.userId)]})
    await load()
  })
}
</script>

<template>
  <section class="view-page">
    <header class="view-heading"><h1>联系人</h1><button class="outline" :disabled="busy" @click="run(load)">刷新</button></header>
    <div class="contact-grid">
      <section class="surface contact-main">
        <nav class="tabs-strip" aria-label="联系人分类">
          <button :aria-pressed="tab==='friends'" @click="tab='friends'">好友 <span>{{ friends.length }}</span></button>
          <button :aria-pressed="tab==='groups'" @click="tab='groups'">群聊 <span>{{ groups.length }}</span></button>
          <button :aria-pressed="tab==='applications'" @click="tab='applications'">申请 <span>{{ applications.length }}</span></button>
        </nav>
        <template v-if="tab==='friends'">
          <p v-if="!friends.length" class="section-empty">暂无好友</p>
          <article v-for="person in friends" :key="person.userId" class="data-row">
            <span class="avatar">{{ (person.nickname || '友').slice(0,1) }}</span>
            <div class="row-detail"><strong>{{ person.nickname || '好友' }}</strong><p>{{ Number(person.status)===1 ? '已拉黑' : person.signature }}</p></div>
            <div class="row-actions">
              <button v-if="person.sessionId && Number(person.status)!==1" class="quiet" @click="open(person.sessionId)">发消息</button>
              <button class="quiet" :disabled="busy" @click="changeFriend(person,'block')">{{ Number(person.status)===1 ? '解除拉黑' : '拉黑' }}</button>
              <button class="quiet muted" :disabled="busy" @click="changeFriend(person,'friend')">删除好友</button>
            </div>
          </article>
        </template>
        <template v-else-if="tab==='groups'">
          <p v-if="!groups.length" class="section-empty">尚未加入群聊</p>
          <article v-for="group in groups" :key="group.sessionId" class="data-row">
            <span class="avatar group"><UsersRound :size="17"/></span>
            <div class="row-detail"><strong>{{ group.name || group.sessionName || '群聊' }}</strong><p>{{ group.memberCount ?? group.membersCount }} 位成员</p></div>
            <button class="quiet" @click="open(group.sessionId)">打开群聊</button>
          </article>
        </template>
        <template v-else>
          <p v-if="!applications.length" class="section-empty">暂无好友申请</p>
          <article v-for="(application,index) in applications" :key="application.applicationId || index" class="data-row">
            <span class="avatar"><ContactRound :size="17"/></span>
            <div class="row-detail"><strong>{{ application.nickname || '联系人' }}</strong>
              <p>{{ Number(application.isReceiver)===1 ? '收到' : '发出' }} · {{ states[Number(application.status)] }} {{ application.msg }}</p>
            </div>
            <div v-if="Number(application.isReceiver)===1 && [0,3].includes(Number(application.status))" class="row-actions">
              <button class="outline" :disabled="busy" @click="decide(application,1)">通过</button>
              <button class="quiet" :disabled="busy" @click="decide(application,2)">拒绝</button>
            </div>
          </article>
        </template>
      </section>
      <section class="surface contact-search">
        <header><h2>查找用户</h2></header>
        <form class="inline-row" @submit.prevent="search">
          <input v-model="keyword" placeholder="昵称或邮箱" aria-label="昵称或邮箱" required>
          <button class="outline icon-button" aria-label="查找" :disabled="busy"><Search :size="18"/></button>
        </form>
        <article v-if="found" class="data-row">
          <div class="row-detail"><strong>{{ found.nickname || '轻聊用户' }}</strong><p>{{ found.signature }}</p></div>
          <button v-if="found.sessionId && Number(found.status)===0" class="quiet" @click="open(found.sessionId)">发消息</button>
          <button v-else class="quiet" @click="apply">添加好友</button>
        </article>
        <p v-else-if="searched" class="muted">未找到可添加的用户。</p>
      </section>
    </div>
    <p v-if="message" :class="{error:failed}" class="view-status" role="status">{{ message }}</p>
    <ModalPanel ref="modal" :title="'添加 ' + (found?.nickname || '联系人')">
      <form class="form-stack" @submit.prevent="add">
        <label>申请说明<input v-model="draft" maxlength="100" placeholder="你好，希望加你为好友" required></label>
        <p v-if="message && failed" class="error" role="status">{{ message }}</p>
        <button class="primary" :disabled="busy">发送申请</button>
      </form>
    </ModalPanel>
  </section>
</template>

