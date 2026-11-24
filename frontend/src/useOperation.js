import { ref } from 'vue'

export function useOperation() {
  const busy = ref(false)
  const message = ref('')
  const failed = ref(false)
  function feedback(text='', error=false) { message.value=text; failed.value=error }
  async function run(action) {
    if (busy.value) return
    busy.value=true
    feedback()
    try { return await action() }
    catch (err) { feedback(err.message === 'Failed to fetch' ? '暂时连接不上服务，请稍后重试。' : err.message,true) }
    finally { busy.value=false }
  }
  return { busy, message, failed, feedback, run }
}

