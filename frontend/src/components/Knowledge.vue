<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { FileText, RefreshCw, Search, UploadCloud } from '@lucide/vue'
import { api, currentUser, encode, id, post } from '../api'
import { useOperation } from '../useOperation'

const props = defineProps({ preferred:String })
const { busy, message, failed, feedback, run } = useOperation()
const base = '/api/ai/knowledge/'
const sessions = ref([])
const scope = ref('')
const docs = ref([])
const fileInput = ref(null)
const selectedFile = ref(null)
const question = ref('')
const answer = ref('')
const sources = ref([])
const preview = ref(null)
const reading = ref(false)
let version = 0

const visibleSessions = computed(() => sessions.value.filter(room => Number(room.type)!==2))
const asking = computed(() => scope.value==='personal' ? id(sessions.value.find(room=>Number(room.type)===2)?.sessionId) : scope.value)
const roomName = computed(() => visibleSessions.value.find(room=>id(room.sessionId)===scope.value)?.name)
const statuses = { READY:'可用', FAILED:'索引失败', PENDING:'等待索引', INDEXING:'正在索引', PROCESSING:'正在索引', QUEUED:'等待索引' }
function params(value) {
  return new URLSearchParams(value==='personal' ? {scope:'PERSONAL'} : {scope:'SESSION',sessionId:value})
}
function clear() { preview.value=null; answer.value=''; sources.value=[] }

async function loadDocuments(value=scope.value) {
  if (!value) return
  const request=++version
  reading.value=true
  try {
    const result=await api(base+'documents?'+params(value))
    if (request===version) docs.value=result||[]
  } catch (error) { if (request===version) feedback(error.message,true) }
  finally { if (request===version) reading.value=false }
}
onMounted(() => run(async () => {
  sessions.value=await api('/api/user/agent/sessions')||[]
  scope.value=id(visibleSessions.value.find(room=>id(room.sessionId)===props.preferred)?.sessionId)
    || id(visibleSessions.value[0]?.sessionId) || 'personal'
}))
watch(scope, (value) => { clear(); docs.value=[]; feedback(); loadDocuments(value) })

function upload() {
  return run(async () => {
    const value=scope.value
    const file=fileInput.value.files[0]
    if (!value || !file) throw Error('请选择资料和可用的会话。')
    if (file.size>10*1048576) throw Error('文件超过 10 MB。')
    if (value!=='personal' && !confirm('将这份资料共享给当前会话成员？')) return
    const body=new FormData()
    body.append('file',file)
    await api(base+'documents?'+params(value),{method:'POST',body})
    fileInput.value.value=''
    selectedFile.value=null
    if (scope.value===value) await loadDocuments(value)
    feedback('资料已提交，索引完成后即可检索。')
  })
}
function documentAction(doc, action) {
  const path=base+'documents/'+encode(doc.documentId)
  if (action==='remove' && !confirm('删除「'+doc.filename+'」？')) return
  return run(async () => {
    const value=scope.value
    if (action==='read') {
      const text=await api(path+'/text')
      if (value===scope.value) preview.value={title:doc.filename,text}
      return
    }
    if (action==='remove') {
      await api(path,{method:'DELETE'})
      if(value===scope.value) clear()
    } else await post(path+'/retry',{})
    if(value===scope.value) await loadDocuments(value)
  })
}
function ask(asAnswer) {
  return run(async () => {
    if (!asking.value) throw Error('当前资料来源不支持问答。')
    const value=scope.value
    clear()
    const response=await post(base+(asAnswer?'ask':'search'),{sessionId:asking.value,question:question.value.trim()})
    if (value!==scope.value) return
    sources.value=(asAnswer ? response.sources : response)||[]
    answer.value=asAnswer ? response.answer : sources.value.length ? '' : '没有找到匹配的可见资料。'
  })
}
</script>

<template>
  <section class="view-page">
    <header class="view-heading"><h1>知识库</h1>
      <label class="scoped-select">资料来源<select v-model="scope" aria-label="资料来源">
        <option value="" disabled>选择资料来源</option><option value="personal">我的资料</option>
        <option v-for="room in visibleSessions" :key="room.sessionId" :value="id(room.sessionId)">{{ room.name }}</option>
      </select></label>
    </header>
    <div class="library-grid">
      <section class="surface library-list">
        <header><div><h2>{{ roomName || '我的资料' }}</h2><p class="muted">{{ docs.length }} 份资料</p></div>
          <button class="icon-button" aria-label="刷新资料" title="刷新资料" :disabled="reading" @click="loadDocuments()"><RefreshCw :size="17"/></button>
        </header>
        <form class="upload-line" @submit.prevent="upload">
          <label class="file-picker"><UploadCloud :size="17"/><span>{{ selectedFile?.name || '选择资料' }}</span>
            <input ref="fileInput" type="file" aria-label="选择资料" accept=".txt,.md,.pdf,.docx" @change="selectedFile=$event.target.files[0]">
          </label>
          <button class="primary" :disabled="busy || !selectedFile">上传</button>
        </form>
        <p class="muted file-hint">TXT、MD、PDF、DOCX · 单个文件最多 10 MB</p>
        <p v-if="reading || !docs.length" class="section-empty">{{ reading ? '正在读取资料…' : '此范围暂无资料' }}</p>
        <article v-for="doc in docs" :key="doc.documentId" class="data-row">
          <span class="avatar file-avatar"><FileText :size="18"/></span>
          <div class="row-detail"><strong>{{ doc.filename }}</strong><p>{{ statuses[doc.status] || doc.status }} · {{ doc.chunkCount }} 个片段</p>
            <p v-if="doc.lastError">{{ doc.lastError }}</p>
          </div>
          <div class="row-actions">
            <button v-if="doc.status==='READY'" class="quiet" :disabled="busy" @click="documentAction(doc,'read')">查看</button>
            <button v-if="doc.status==='FAILED' && id(doc.ownerId)===currentUser()" class="quiet" :disabled="busy" @click="documentAction(doc,'retry')">重试索引</button>
            <button v-if="id(doc.ownerId)===currentUser()" class="quiet muted" :disabled="busy" @click="documentAction(doc,'remove')">删除</button>
          </div>
        </article>
        <div v-if="preview" class="doc-preview">
          <header><strong>{{ preview.title }}</strong><button class="quiet" @click="preview=null">收起</button></header>
          <pre>{{ preview.text }}</pre>
        </div>
      </section>
      <section class="surface library-ask">
        <header><div><h2>资料问答</h2><p class="muted">{{ roomName ? '搜索「'+roomName+'」的共享资料' : '搜索个人资料和助手会话资料' }}</p></div><Search :size="19"/></header>
        <form class="form-stack" @submit.prevent="ask(true)">
          <label class="sr-only" for="question">问题</label><textarea id="question" v-model="question" rows="3" maxlength="2000" required placeholder="你想从资料里了解什么？"></textarea>
          <div class="inline-row"><button class="primary" :disabled="busy || !asking || !question.trim()">问答</button>
            <button class="outline" type="button" :disabled="busy || !asking || !question.trim()" @click="ask(false)">只查资料</button></div>
        </form>
        <p v-if="answer" class="answer">{{ answer }}</p>
        <div v-if="sources.length" class="sources">
          <h3>引用资料</h3>
          <article v-for="(source,index) in sources" :key="index">
            <strong>{{ source.filename }}</strong><span>片段 {{ source.chunkNo }} · {{ source.scope==='PERSONAL' ? '个人资料' : '会话资料' }}</span><p>{{ source.excerpt }}</p>
          </article>
        </div>
      </section>
    </div>
    <p v-if="message" class="view-status" :class="{error:failed}" role="status">{{ message }}</p>
  </section>
</template>

