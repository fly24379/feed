<script setup>
import { onMounted, ref } from 'vue'
import { endpoints } from '../api'
import { notify } from '../store'
import UiIcon from '../components/UiIcon.vue'

const metrics = ref(null)
const automation = ref(null)
const shadow = ref(null)
const loading = ref(true)
const eventId = ref('')
const replaying = ref(false)
const policyAuthorId = ref('')
const policyMode = ref('PULL')
const policyReason = ref('')
const historyLimit = ref(100)
const policy = ref(null)
const savingPolicy = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [outbox, autoPolicy, shadowRead] = await Promise.all([
      endpoints.outboxMetrics(), endpoints.fanoutAutomation(), endpoints.feedShadowMetrics(),
    ])
    metrics.value = outbox
    automation.value = autoPolicy
    shadow.value = shadowRead
  }
  catch (error) { notify(error.message, 'error') }
  finally { loading.value = false }
}

async function runAutomation() {
  savingPolicy.value = true
  try {
    automation.value = await endpoints.runFanoutAutomation()
    notify(`已评估 ${automation.value.evaluatedThisRun} 位作者，转为 PULL ${automation.value.promotedThisRun} 位`)
  } catch (error) { notify(error.message, 'error') }
  finally { savingPolicy.value = false }
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

async function loadPolicy() {
  if (!policyAuthorId.value) return
  savingPolicy.value = true
  try {
    policy.value = await endpoints.fanoutPolicy(policyAuthorId.value)
    policyMode.value = policy.value.mode
    policyReason.value = policy.value.reason || ''
  } catch (error) { notify(error.message, 'error') }
  finally { savingPolicy.value = false }
}

async function savePolicy() {
  if (!policyAuthorId.value) return
  savingPolicy.value = true
  try {
    const result = await endpoints.switchFanoutPolicy(policyAuthorId.value, {
      mode: policyMode.value,
      reason: policyReason.value.trim() || null,
      historyLimit: Number(historyLimit.value) || 0,
    })
    policy.value = result.policy
    notify(`已切换为 ${policyMode.value}：更新 ${result.historyUpdated} 条历史动态，补写 ${result.inboxRowsInserted} 条 Inbox`)
  } catch (error) { notify(error.message, 'error') }
  finally { savingPolicy.value = false }
}

async function resetPolicy() {
  if (!policyAuthorId.value) return
  savingPolicy.value = true
  try {
    await endpoints.resetFanoutPolicy(policyAuthorId.value)
    policy.value = null
    policyMode.value = 'PUSH'
    policyReason.value = ''
    notify(`用户 ${policyAuthorId.value} 已恢复默认 PUSH 扩散`)
  } catch (error) { notify(error.message, 'error') }
  finally { savingPolicy.value = false }
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
    <section v-if="automation && shadow" class="metric-grid">
      <article><span>自动判定</span><strong>{{ automation.lastEvaluated }}</strong><small>最近评估作者</small></article>
      <article><span>影子读取</span><strong>{{ shadow.reads }}</strong><small>采样 {{ Math.round(shadow.sampleRate * 100) }}%</small></article>
      <article :class="{ alert: shadow.mismatches > 0 }"><span>Feed 差异</span><strong>{{ shadow.mismatches }}</strong><small>Mismatch</small></article>
      <article :class="{ alert: shadow.lastDuplicates > 0 }"><span>最近重复</span><strong>{{ shadow.lastDuplicates }}</strong><small>Duplicate</small></article>
    </section>
    <section class="admin-replay card-surface">
      <div><span class="rail-icon"><UiIcon name="refresh" /></span><div><h2>重放 FAILED 事件</h2><p>仅 FAILED 状态的 Outbox 事件可以重放，尝试次数会被清零。</p></div></div>
      <form @submit.prevent="replay"><input v-model="eventId" type="number" min="1" placeholder="事件 ID" required><button class="primary-button" :disabled="replaying">{{ replaying ? '处理中…' : '确认重放' }}</button></form>
    </section>
    <section class="admin-replay admin-policy card-surface">
      <div><span class="rail-icon"><UiIcon name="people" /></span><div><h2>作者扩散策略</h2><p>切换模式时可回填最近的历史动态；PUSH 会补写好友 Inbox，PULL 由首页双来源合并并自动去重。</p></div></div>
      <form @submit.prevent="savePolicy">
        <input v-model="policyAuthorId" type="number" min="1" placeholder="作者用户 ID" required>
        <select v-model="policyMode" aria-label="扩散模式"><option value="PUSH">PUSH 写扩散</option><option value="PULL">PULL 读扩散</option></select>
        <input v-model="policyReason" maxlength="128" placeholder="调整原因（可选）">
        <input v-model.number="historyLimit" type="number" min="0" max="5000" placeholder="历史回填数量">
        <button class="primary-button" :disabled="savingPolicy">{{ savingPolicy ? '处理中…' : '切换并回填' }}</button>
      </form>
      <div class="policy-actions">
        <button class="secondary-button" type="button" :disabled="savingPolicy" @click="runAutomation">立即执行自动判定</button>
        <button class="secondary-button" type="button" :disabled="savingPolicy || !policyAuthorId" @click="loadPolicy">查询当前策略</button>
        <button class="secondary-button danger" type="button" :disabled="savingPolicy || !policyAuthorId" @click="resetPolicy">恢复默认 PUSH</button>
        <span v-if="policy" class="status-pill">当前：{{ policy.mode }} · {{ policy.source || (policy.explicit ? 'MANUAL' : '系统默认') }}</span>
      </div>
    </section>
  </div>
</template>
