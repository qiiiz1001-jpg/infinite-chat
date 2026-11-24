<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { identity, state } from './api'
import LoginPage from './components/LoginPage.vue'
import Workspace from './components/Workspace.vue'

const loginNotice = ref('')
const expire = () => { loginNotice.value = '登录已过期，请重新登录。'; identity(null) }
watch(() => state.user?.accessToken, token => { if (token) loginNotice.value = '' })
onMounted(() => document.addEventListener('qingliao:expired', expire))
onUnmounted(() => document.removeEventListener('qingliao:expired', expire))
</script>

<template>
  <Workspace v-if="state.user?.accessToken" :key="state.generation" />
  <LoginPage v-else :session-notice="loginNotice" />
</template>

