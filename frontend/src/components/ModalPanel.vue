<script setup>
import { ref } from 'vue'
import { X } from '@lucide/vue'
defineProps({ title:String })
const emit = defineEmits(['close'])
const dialog = ref(null)
function open() { if (!dialog.value.open) dialog.value.showModal() }
function close() { dialog.value.close() }
defineExpose({ open,close })
</script>

<template>
  <dialog ref="dialog" class="modal" :aria-label="title" @close="emit('close')">
    <header><h2>{{ title }}</h2><button class="icon-button" aria-label="关闭" title="关闭" @click="close"><X :size="20"/></button></header>
    <div class="modal-body"><slot /></div>
  </dialog>
</template>

