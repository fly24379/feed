<script setup>
import { computed } from 'vue'
import UiIcon from './UiIcon.vue'
import UserAvatar from './UserAvatar.vue'
import { isAdmin, store } from '../store'

const props = defineProps({ route: { type: String, required: true } })
const emit = defineEmits(['navigate', 'logout'])

const navItems = computed(() => {
  const items = [
    { route: 'feed', label: '动态', icon: 'home' },
    { route: 'people', label: '关系', icon: 'people' },
    { route: 'notifications', label: '通知', icon: 'bell', badge: store.unreadCount },
    { route: 'profile', label: '我的', icon: 'user' },
  ]
  if (isAdmin()) items.push({ route: 'admin', label: '运维', icon: 'shield' })
  return items
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <button class="wordmark" type="button" @click="emit('navigate', 'feed')" aria-label="返回动态">
        <span class="brand-mark">F</span>
        <span><strong>Friend Feed</strong><small>只与重要的人分享</small></span>
      </button>

      <nav class="side-nav" aria-label="主导航">
        <button v-for="item in navItems" :key="item.route" type="button"
                :class="['nav-item', { active: props.route === item.route }]"
                @click="emit('navigate', item.route)">
          <span class="nav-icon"><UiIcon :name="item.icon" /></span>
          <span>{{ item.label }}</span>
          <span v-if="item.badge" class="nav-badge">{{ item.badge > 99 ? '99+' : item.badge }}</span>
        </button>
      </nav>

      <div class="sidebar-user">
        <UserAvatar :profile="store.user" :size="42" />
        <div class="min-w-0"><strong>{{ store.user?.nickname }}</strong><small>@{{ store.user?.username }}</small></div>
        <button class="icon-button quiet" type="button" title="退出登录" @click="emit('logout')">
          <UiIcon name="logout" :size="18" />
        </button>
      </div>
    </aside>

    <main class="main-stage"><slot /></main>

    <nav class="bottom-nav" aria-label="移动端导航">
      <button v-for="item in navItems.slice(0, 4)" :key="item.route" type="button"
              :class="{ active: props.route === item.route }" @click="emit('navigate', item.route)">
        <span class="bottom-icon"><UiIcon :name="item.icon" :size="21" /><i v-if="item.badge"></i></span>
        <small>{{ item.label }}</small>
      </button>
    </nav>
  </div>
</template>
