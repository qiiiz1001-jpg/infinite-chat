<script setup>
import { computed, onUnmounted, ref } from 'vue'
import { ArrowRight, MessagesSquare } from '@lucide/vue'
import { api, identity } from '../api'

const props=defineProps({ sessionNotice:String })
const mode = ref('password')
const account = ref('')
const password = ref('')
const confirmPassword = ref('')
const nickname = ref('')
const code = ref('')
const busy = ref(false)
const captchaBusy = ref(false)
const error = ref(props.sessionNotice || '')
const notice = ref('')
const remaining = ref(0)
let timer
const supportsTrial = computed(() => ['localhost','127.0.0.1','[::1]'].includes(location.hostname) && mode.value === 'password')
const titles = { password:'登录轻聊', code:'验证码登录', register:'创建轻聊账号' }
const tabs = { password:'密码登录', code:'验证码登录', register:'注册' }
function change(next) { mode.value = next; error.value = ''; notice.value = '' }

async function captcha() {
  if (remaining.value || captchaBusy.value) return
  if (!account.value.includes('@')) { error.value='请输入电子邮箱。'; return }
  captchaBusy.value = true
  error.value = ''
  try {
    await api('/api/user/sendCaptcha?' + new URLSearchParams({targetEmail:account.value.trim()}), {auth:false})
    notice.value = '验证码已发送，请查看邮箱。'
    remaining.value = 60
    clearInterval(timer)
    timer = setInterval(() => { if (--remaining.value <= 0) clearInterval(timer) }, 1000)
  } catch (err) { error.value=err.message }
  finally { captchaBusy.value = false }
}
onUnmounted(() => clearInterval(timer))

async function submit(trial=false) {
  if (busy.value) return
  busy.value=true
  error.value=''
  try {
    const name = trial ? '123456' : account.value.trim()
    const email = supportsTrial.value && name === '123456' ? 'demo-a@example.com' : name
    const secret = trial ? '123456' : password.value
    const body = mode.value === 'code' ? { email, code:code.value }
      : mode.value === 'register' ? { email, password:secret, confirmPassword:confirmPassword.value, nickname:nickname.value.trim(), code:code.value }
        : { email, password:secret }
    if (mode.value === 'register' && secret !== confirmPassword.value) throw Error('两次密码不一致。')
    const path = { code:'login/code', password:'login/password', register:'register' }[mode.value]
    identity(await api('/api/user/' + path, { method:'POST', body, auth:false }))
  } catch (err) { error.value = err.message === 'Failed to fetch' ? '暂时连接不上服务。' : err.message }
  finally { busy.value=false }
}
</script>

<template>
  <main class="login-shell">
    <aside class="login-aside">
      <header class="brand"><span class="brand-mark"><MessagesSquare :size="19"/></span>轻聊</header>
      <div class="login-aside-content">
        <h1>轻聊工作区</h1>
        <p>消息、会话资料与协作任务</p>
      </div>
      <div class="login-illustration" aria-hidden="true">
        <div class="illustration-rail"></div><div class="illustration-list"><i></i><i></i><i></i></div>
        <div class="illustration-chat"><i></i><i></i><i></i></div>
      </div>
    </aside>
    <section class="login-content">
      <div>
        <h2>{{ titles[mode] }}</h2>
        <nav class="login-tabs" aria-label="登录方式">
          <button v-for="(label,key) in tabs" :key="key" :aria-pressed="mode === key" @click="change(key)">{{ label }}</button>
        </nav>
        <form class="form-stack" @submit.prevent="submit()">
          <label>{{ supportsTrial ? '邮箱或试用账号' : '电子邮箱' }}<input v-model="account" :type="supportsTrial ? 'text' : 'email'" inputmode="email" autocomplete="username" required></label>
          <label v-if="mode==='register'">昵称<input v-model="nickname" autocomplete="nickname" maxlength="50" required></label>
          <label v-if="mode!=='code'">密码<input v-model="password" type="password" maxlength="20" :autocomplete="mode==='register' ? 'new-password' : 'current-password'" required></label>
          <label v-if="mode==='register'">确认密码<input v-model="confirmPassword" type="password" maxlength="20" autocomplete="new-password" required></label>
          <label v-if="mode!=='password'">邮箱验证码
            <span class="inline-row">
              <input v-model="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required>
              <button type="button" class="outline" :disabled="!!remaining || captchaBusy" @click="captcha">{{ remaining ? remaining + ' 秒后重发' : '发送验证码' }}</button>
            </span>
          </label>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <p v-else-if="notice" class="muted" role="status">{{ notice }}</p>
          <button class="primary login-primary" :disabled="busy">{{ mode === 'register' ? '注册并登录' : '登录' }} <ArrowRight :size="17"/></button>
        </form>
        <button v-if="supportsTrial" class="quiet login-demo" :disabled="busy" @click="submit(true)">使用本机试用账号</button>
      </div>
    </section>
  </main>
</template>

