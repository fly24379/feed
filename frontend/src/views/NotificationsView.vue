<script setup>
import { onMounted, ref } from 'vue'
import { endpoints } from '../api'
import { notify, refreshUnread, store } from '../store'
import UiIcon from '../components/UiIcon.vue'
import UserAvatar from '../components/UserAvatar.vue'

const items = ref([])
const page = ref(null)
const unreadOnly = ref(false)
const loading = ref(true)

onMounted(() => load(true))

async function load(reset = true) {
  loading.value = true
  try {
    const result = await endpoints.notifications(unreadOnly.value, reset ? null : page.value?.nextBeforeId, 30)
    const normalized = result.items.map((item) => ({ ...item, read: Boolean(item.readAt) }))
    items.value = reset ? normalized : [...items.value, ...normalized]
    page.value = result
    store.unreadCount = result.unreadCount
  } catch (error) { notify(error.message, 'error') }
  finally { loading.value = false }
}

async function markRead(item) {
  if (item.read) return
  try {
    await endpoints.markNotificationRead(item.id)
    item.read = true
    item.readAt = new Date().toISOString()
    await refreshUnread()
  } catch (error) { notify(error.message, 'error') }
}

async function markAll() {
  try {
    const result = await endpoints.markAllNotificationsRead()
    items.value.forEach((item) => { item.read = true; item.readAt ||= new Date().toISOString() })
    store.unreadCount = 0
    notify(result.updatedCount ? `已将 ${result.updatedCount} 条通知标为已读` : '没有未读通知')
  } catch (error) { notify(error.message, 'error') }
}

function iconFor(type) {
  if (type === 'POST_LIKED') return 'heart'
  if (type === 'POST_COMMENTED') return 'comment'
  return 'people'
}

function formatDate(value) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}
</script>

<template>
  <div class="single-page narrow-page">
    <header class="page-heading heading-actions">
      <div><p class="eyebrow">UPDATES</p><h1>通知</h1><p>{{ store.unreadCount ? `${store.unreadCount} 条未读消息` : '你已经读完所有新消息' }}</p></div>
      <button class="secondary-button" type="button" @click="markAll"><UiIcon name="check" :size="17" /> 全部已读</button>
    </header>
    <label class="switch-row"><input v-model="unreadOnly" type="checkbox" @change="load(true)"><span class="switch"></span><span>只看未读</span></label>

    <section class="notification-list card-surface">
      <div v-if="loading && !items.length" class="list-loading">正在加载通知…</div>
      <button v-for="item in items" v-else :key="item.id" type="button"
              :class="['notification-row', { unread: !item.read }]" @click="markRead(item)">
        <div class="notification-avatar"><UserAvatar :profile="item.actor" :size="46" /><span><UiIcon :name="iconFor(item.type)" :size="13" /></span></div>
        <div class="notification-copy"><p>{{ item.message }}</p><span>{{ formatDate(item.createdAt) }}</span></div>
        <i v-if="!item.read" class="unread-dot" aria-label="未读"></i>
      </button>
      <div v-if="!loading && !items.length" class="empty-state compact-empty"><div class="empty-illustration"><UiIcon name="bell" :size="30" /></div><h2>暂无通知</h2><p>好友申请和动态互动会出现在这里。</p></div>
    </section>
    <button v-if="page?.hasMore" class="secondary-button load-more" type="button" :disabled="loading" @click="load(false)">加载更多</button>
  </div>
</template>
