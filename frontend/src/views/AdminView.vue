<script setup>
import { onMounted, ref } from 'vue'
import { endpoints } from '../api'
import { notify } from '../store'
import UiIcon from '../components/UiIcon.vue'

const metrics = ref(null)
const loading = ref(true)
const eventId = ref('')
const replaying = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try { metrics.value = await endpoints.outboxMetrics() }
  catch (error) { notify(error.message, 'error') }
  finally { loading.value = false }
}

async function replay() {
  if (!eventId.value) return
  replaying.value = true
  try {
    await endpoints.replayOutbox(eventId.value)
    notify(`事件 ${eventId.value} 已重新进入投递队列`)
    eventId.value = ''
    await load()
  } catch (error) { notify(error.message, 'error') }
  finally { replaying.value = false }
}
</script>

<template>
  <div class="single-page narrow-page">
    <header class="page-heading heading-actions"><div><p class="eyebrow">OPERATIONS</p><h1>Outbox 运维</h1><p>查看异步扩散链路健康状态并重放死信。</p></div><button class="icon-button soft" @click="load"><UiIcon name="refresh" /></button></header>
    <div v-if="loading" class="list-loading">正在读取指标…</div>
    <section v-else-if="metrics" class="metric-grid">
      <article><span>待完成事件</span><strong>{{ metrics.backlog }}</strong><small>Backlog</small></article>
      <article :class="{ alert: metrics.failed > 0 }"><span>死信事件</span><strong>{{ metrics.failed }}</strong><small>Failed</small></article>
      <article><span>最老积压</span><strong>{{ Number(metrics.oldestBacklogAgeSeconds).toFixed(1) }}s</strong><small>Oldest age</small></article>
      <article><span>平均延迟</span><strong>{{ Number(metrics.averageProcessingLatencySeconds).toFixed(2) }}s</strong><small>5 分钟窗口</small></article>
    </section>
    <section class="admin-replay card-surface">
      <div><span class="rail-icon"><UiIcon name="refresh" /></span><div><h2>重放 FAILED 事件</h2><p>仅 FAILED 状态的 Outbox 事件可以重放，尝试次数会被清零。</p></div></div>
      <form @submit.prevent="replay"><input v-model="eventId" type="number" min="1" placeholder="事件 ID" required><button class="primary-button" :disabled="replaying">{{ replaying ? '处理中…' : '确认重放' }}</button></form>
    </section>
  </div>
</template>
