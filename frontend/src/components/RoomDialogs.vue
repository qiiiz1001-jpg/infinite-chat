<script setup>
import { computed, reactive, ref } from 'vue'
import { Gift } from '@lucide/vue'
import { api, currentUser, encode, id, post } from '../api'
import { useOperation } from '../useOperation'
import ModalPanel from './ModalPanel.vue'

const emit = defineEmits(['roomsChanged','status'])
const {busy,message,failed,feedback,run}=useOperation()
const modal=ref(null)
const kind=ref('')
const selected=ref(null)
const users=ref([])
const choices=ref([])
const packetId=ref('')
const packetView=ref(null)
const ownPacket=ref(false)
const fields=reactive({ amount:null, count:1, type:0, note:'' })
let view=0
const displayTitle=computed(()=>({
  create:'创建群聊',invite:'邀请好友',members:'群成员',packet:'发送红包',details:'红包详情'
}[kind.value] || '会话'))
function show(next, room) {
  kind.value=next
  selected.value=room
  choices.value=[]
  users.value=[]
  feedback()
  modal.value.open()
}
async function loadPeople(path, version) {
  const result=await api(path)
  if (version===view) users.value=result?.list||[]
}
const friendPath = () => '/api/contact/'+encode(currentUser())+'/friend?pageNum=1&pageSize=100'
function group() {
  show('create')
  const version=++view
  run(()=>loadPeople(friendPath(),version))
}
function invite() {
  show('invite',selected.value)
  const version=++view
  run(()=>loadPeople(friendPath(),version))
}
function members(room) {
  show('members',room)
  const version=++view
  run(()=>loadPeople('/api/group/'+encode(room.sessionId)+'/members?pageNum=1&pageSize=100',version))
}
function packet(room) {
  ++view
  show('packet',room)
  Object.assign(fields,{amount:null,count:1,type:0,note:''})
}
async function readPacket() {
  const version=view
  const data=await api('/api/chat/redPacket/?redPacketId='+encode(packetId.value))
  if(version===view) packetView.value=data
}
function packetDetails(target,mine) {
  ++view
  show('details')
  ownPacket.value=mine
  packetId.value=id(target)
  packetView.value=null
  run(readPacket)
}
defineExpose({group,members,packet,packetDetails})
function finishGroup() {
  return run(async()=>{
    if(!choices.value.length)throw Error('至少选择一位好友。')
    if(kind.value==='create'){
      const result=await post('/api/group',{creatorId:currentUser(),memberIds:choices.value})
      modal.value.close()
      emit('roomsChanged',result.sessionId)
      emit('status',result.failedMemberIds?.length?'群已创建，部分成员未能加入。':'群聊已创建。')
    } else {
      const result=await post('/api/group/invite',{sessionId:selected.value.sessionId,inviterId:currentUser(),inviteeIds:choices.value})
      feedback(result.failedMemberIds?.length?'已处理邀请，部分好友未能加入。':'邀请已处理。')
      choices.value=[]
    }
  })
}
function exitGroup() {
  if(!confirm('确定退出当前群聊？'))return
  run(async()=>{
    await post('/api/group/exit',{sessionId:selected.value.sessionId,userId:currentUser()})
    modal.value.close()
    emit('roomsChanged')
  })
}
function sendPacket() {
  return run(async()=>{
    const room=selected.value
    const totalCount=room.type===1 ? Number(fields.count) : 1
    const totalAmount=Number(fields.amount)
    if (!Number.isFinite(totalAmount) || Math.round(totalAmount*100)<totalCount)throw Error('每份至少 1 分。')
    await post('/api/chat/redPacket/send',{
      sessionId:room.sessionId,sessionType:room.type,receiverId:room.receiverId,senderId:currentUser(),
      type:3,clientMessageId:crypto.randomUUID(),
      body:{redPacketType:Number(fields.type),totalAmount,totalCount,redPacketWrapperText:fields.note.trim()||'恭喜发财'}
    })
    modal.value.close()
    emit('status','红包已发送。')
  })
}
function claim() {
  return run(async()=>{
    const result=await post('/api/chat/redPacket/receive',{userId:currentUser(),redPacketId:packetId.value})
    if(result.success===false)throw Error(result.message||'领取失败。')
    await readPacket()
    feedback(result.message||'红包已领取。')
  })
}
</script>

<template>
  <ModalPanel ref="modal" :title="displayTitle">
    <form v-if="kind==='create'||kind==='invite'" class="form-stack" @submit.prevent="finishGroup">
      <fieldset><legend>选择好友</legend>
        <p v-if="!users.length" class="muted">{{ busy ? '正在读取好友…' : '请先在联系人中添加好友。' }}</p>
        <label v-for="person in users" :key="person.userId" class="check-row">
          <input v-model="choices" type="checkbox" :value="id(person.userId)"> {{ person.nickname || '好友' }}
        </label>
      </fieldset>
      <button class="primary" :disabled="busy || !choices.length">{{ kind==='create'?'创建群聊':'发送邀请' }}</button>
    </form>
    <template v-else-if="kind==='members'">
      <div v-for="person in users" :key="person.userId" class="data-row">
        <span class="avatar">{{ (person.nickname||'聊').slice(0,1) }}</span>
        <strong>{{ person.nickname || '群成员' }}</strong>
        <span v-if="id(person.userId)===currentUser()" class="muted">你</span>
      </div>
      <div class="inline-row"><button class="primary" :disabled="busy" @click="invite">邀请好友</button><button class="outline" :disabled="busy" @click="exitGroup">退出群聊</button></div>
    </template>
    <form v-else-if="kind==='packet'" class="form-stack" @submit.prevent="sendPacket">
      <p class="muted">使用站内余额。</p>
      <label>总金额（元）<input v-model.number="fields.amount" type="number" min=".01" step=".01" required></label>
      <template v-if="selected.type===1">
        <label>份数<input v-model.number="fields.count" type="number" min="1" step="1" required></label>
        <label>类型<select v-model.number="fields.type"><option :value="0">普通红包</option><option :value="1">拼手气红包</option></select></label>
      </template>
      <label>祝福语<input v-model="fields.note" maxlength="100" placeholder="恭喜发财"></label>
      <button class="primary" :disabled="busy">发送红包</button>
    </form>
    <template v-else-if="kind==='details' && packetView">
      <div class="packet-summary"><Gift :size="22"/><strong>{{ packetView.redPacketWrapperText || '红包' }}</strong></div>
      <p class="muted">总额 ¥{{ packetView.totalAmount }} · 已领取 {{ packetView.receivedCount }}/{{ packetView.totalCount }} 份</p>
      <div v-for="(record,index) in packetView.receiveRecords || []" :key="index" class="data-row">
        <span class="row-detail">{{ record.nickname || record.receiverNickname || '会话成员' }}</span><strong>¥{{ record.amount }}</strong>
      </div>
      <button v-if="!ownPacket && packetView.receivedCount<packetView.totalCount" class="primary" :disabled="busy" @click="claim">领取红包</button>
    </template>
    <p v-if="message" :class="{error:failed}" role="status">{{ message }}</p>
  </ModalPanel>
</template>

