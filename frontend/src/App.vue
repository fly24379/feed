<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { endpoints } from './api'
import { bootstrapSession, clearSession, isAdmin, notify, setSession, store } from './store'
import AppShell from './components/AppShell.vue'
import UiIcon from './components/UiIcon.vue'
import AuthView from './views/AuthView.vue'
import FeedView from './views/FeedView.vue'
import PeopleView from './views/PeopleView.vue'
import NotificationsView from './views/NotificationsView.vue'
import ProfileView from './views/ProfileView.vue'
import AdminView from './views/AdminView.vue'

const route = ref(routeFromHash())
const views = { feed: FeedView, people: PeopleView, notifications: NotificationsView, profile: ProfileView, admin: AdminView }
const currentView = computed(() => views[route.value] || FeedView)

function routeFromHash() {
  return window.location.hash.replace(/^#\/?/, '').split('/')[0] || 'feed'
}

function onHashChange() {
  const next = routeFromHash()
  route.value = next === 'admin' && !isAdmin() ? 'feed' : (views[next] ? next : 'feed')
}

function navigate(next) {
  window.location.hash = `#/${next}`
}

async function authenticated(access) {
  setSession(access)
  try {
    store.user = await endpoints.me()
    store.profileCache.set(store.user.id, store.user)
    navigate('feed')
    notify(`欢迎回来，${store.user.nickname}`)
  } catch (error) {
    clearSession()
    notify(error.message, 'error')
  }
}

function logout(showMessage = true) {
  clearSession()
  window.location.hash = ''
  if (showMessage) notify('已安全退出')
}

function expired() {
  logout(false)
  notify('登录已过期，请重新登录', 'error')
}

onMounted(async () => {
  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('session-expired', expired)
  await bootstrapSession()
  onHashChange()
  if (store.user && !window.location.hash) navigate('feed')
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', onHashChange)
  window.removeEventListener('session-expired', expired)
})
</script>

<template>
  <div v-if="!store.ready" class="boot-screen">
    <div class="brand-mark">F</div><div class="boot-dots"><i></i><i></i><i></i></div><p>正在打开你的动态圈…</p>
  </div>
  <AuthView v-else-if="!store.user" @authenticated="authenticated" />
  <AppShell v-else :route="route" @navigate="navigate" @logout="logout">
    <Transition name="page" mode="out-in"><component :is="currentView" :key="route" /></Transition>
  </AppShell>

  <Transition name="toast">
    <div v-if="store.toast" :class="['toast', store.toast.tone]" role="status">
      <span><UiIcon :name="store.toast.tone === 'error' ? 'close' : 'check'" :size="17" /></span>
      {{ store.toast.message }}
    </div>
  </Transition>
</template>
