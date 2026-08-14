<script setup>
import { onMounted, ref } from 'vue'
import { endpoints } from '../api'
import { notify, store } from '../store'
import UiIcon from '../components/UiIcon.vue'
import UserAvatar from '../components/UserAvatar.vue'

const tab = ref('discover')
const loading = ref(false)
const query = ref('')
const searchResults = ref([])
const searchPage = ref(null)
const friends = ref([])
const blocked = ref([])
const requests = ref([])
const requestBox = ref('INCOMING')
const requestStatus = ref('PENDING')
const busyIds = ref(new Set())

onMounted(() => loadFriends())

async function withBusy(id, action) {
  if (busyIds.value.has(id)) return
  busyIds.value = new Set([...busyIds.value, id])
  try { await action() } catch (error) { notify(error.message, 'error') }
  finally {
    const next = new Set(busyIds.value); next.delete(id); busyIds.value = next
  }
}

async function switchTab(next) {
  tab.value = next
  if (next === 'friends') await loadFriends()
  if (next === 'requests') await loadRequests()
  if (next === 'blocked') await loadBlocked()
}

async function search(reset = true) {
  if (!query.value.trim()) return
  loading.value = true
  try {
    const page = await endpoints.searchUsers(query.value.trim(), reset ? null : searchPage.value?.nextAfterId, 20)
    const filtered = page.items.filter((user) => user.id !== store.user.id)
    searchResults.value = reset ? filtered : [...searchResults.value, ...filtered]
    searchPage.value = page
  } catch (error) { notify(error.message, 'error') }
  finally { loading.value = false }
}

async function loadFriends() {
  loading.value = true
  try { friends.value = await endpoints.friends() }
  catch (error) { notify(error.message, 'error') }
  finally { loading.value = false }
}

async function loadBlocked() {
  loading.value = true
  try { blocked.value = await endpoints.blocks() }
  catch (error) { notify(error.message, 'error') }
  finally { loading.value = false }
}

async function loadRequests() {
  loading.value = true
  try {
    const page = await endpoints.friendRequests(requestBox.value, requestStatus.value)
    requests.value = page.items
  } catch (error) { notify(error.message, 'error') }
  finally { loading.value = false }
}

function sendRequest(user) {
  withBusy(`request-${user.id}`, async () => {
    await endpoints.sendFriendRequest(user.id)
    notify(`已向 ${user.nickname} 发送好友申请`)
  })
}

function blockUser(user) {
  if (!window.confirm(`拉黑 ${user.nickname}？现有好友关系也会被解除。`)) return
  withBusy(`block-${user.id}`, async () => {
    await endpoints.block(user.id)
    friends.value = friends.value.filter((item) => item.id !== user.id)
    notify(`已拉黑 ${user.nickname}`)
  })
}

function removeFriend(user) {
  if (!window.confirm(`从好友中删除 ${user.nickname}？`)) return
  withBusy(`friend-${user.id}`, async () => {
    await endpoints.removeFriend(user.id)
    friends.value = friends.value.filter((item) => item.id !== user.id)
    notify('已删除好友')
  })
}

function unblock(user) {
  withBusy(`unblock-${user.id}`, async () => {
    await endpoints.unblock(user.id)
    blocked.value = blocked.value.filter((item) => item.id !== user.id)
    notify(`已取消拉黑 ${user.nickname}`)
  })
}

function handleRequest(request, action) {
  withBusy(`request-row-${request.id}`, async () => {
    if (action === 'accept') await endpoints.acceptFriendRequest(request.id)
    if (action === 'reject') await endpoints.rejectFriendRequest(request.id)
    if (action === 'withdraw') await endpoints.withdrawFriendRequest(request.id)
    requests.value = requests.value.filter((item) => item.id !== request.id)
    notify(action === 'accept' ? '已成为好友' : action === 'reject' ? '已拒绝申请' : '申请已撤回')
  })
}

function formatDate(value) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}
</script>

<template>
  <div class="single-page narrow-page">
    <header class="page-heading">
      <div><p class="eyebrow">CONNECTIONS</p><h1>关系</h1><p>找到朋友，管理属于你的安静社交圈。</p></div>
    </header>

    <div class="segmented-tabs" role="tablist">
      <button v-for="item in [{id:'discover',label:'发现'}, {id:'friends',label:'好友'}, {id:'requests',label:'申请'}, {id:'blocked',label:'黑名单'}]"
              :key="item.id" type="button" :class="{ active: tab === item.id }" @click="switchTab(item.id)">{{ item.label }}</button>
    </div>

    <section v-if="tab === 'discover'" class="panel-section">
      <form class="search-bar" @submit.prevent="search(true)">
        <UiIcon name="search" :size="20" /><input v-model="query" maxlength="80" placeholder="搜索用户名或昵称" aria-label="搜索用户">
        <button class="primary-button" type="submit" :disabled="!query.trim() || loading">搜索</button>
      </form>
      <div v-if="loading" class="list-loading">正在寻找…</div>
      <div v-else-if="searchResults.length" class="people-list">
        <article v-for="user in searchResults" :key="user.id" class="person-card">
          <UserAvatar :profile="user" :size="50" />
          <div class="person-info"><strong>{{ user.nickname }}</strong><span>@{{ user.username }}</span><p>{{ user.bio || '这个人还没有写简介。' }}</p></div>
          <div class="row-actions">
            <button class="small-button primary-small" type="button" :disabled="busyIds.has(`request-${user.id}`)" @click="sendRequest(user)">
              <UiIcon name="plus" :size="15" /> 加好友
            </button>
            <button class="icon-button quiet" type="button" title="拉黑" @click="blockUser(user)"><UiIcon name="block" :size="18" /></button>
          </div>
        </article>
        <button v-if="searchPage?.hasMore" class="secondary-button load-more" type="button" @click="search(false)">查看更多</button>
      </div>
      <div v-else class="empty-state compact-empty"><div class="empty-illustration"><UiIcon name="search" :size="30" /></div><h2>寻找认识的人</h2><p>输入用户名或昵称，向对方发送好友申请。</p></div>
    </section>

    <section v-else-if="tab === 'friends'" class="panel-section">
      <div class="section-title"><div><h2>我的好友</h2><p>{{ friends.length }} 位好友</p></div><button class="icon-button soft" @click="loadFriends"><UiIcon name="refresh" /></button></div>
      <div v-if="loading" class="list-loading">正在加载好友…</div>
      <div v-else-if="friends.length" class="people-list">
        <article v-for="user in friends" :key="user.id" class="person-card">
          <UserAvatar :profile="user" :size="50" /><div class="person-info"><strong>{{ user.nickname }}</strong><span>@{{ user.username }}</span><p>{{ user.bio || '好友' }}</p></div>
          <div class="row-actions"><button class="small-button" type="button" @click="removeFriend(user)">删除好友</button><button class="icon-button quiet" title="拉黑" @click="blockUser(user)"><UiIcon name="block" :size="18" /></button></div>
        </article>
      </div>
      <div v-else class="empty-state compact-empty"><div class="empty-illustration"><UiIcon name="people" :size="30" /></div><h2>还没有好友</h2><p>去“发现”页搜索并发送申请。</p></div>
    </section>

    <section v-else-if="tab === 'requests'" class="panel-section">
      <div class="request-filters">
        <div class="mini-tabs"><button :class="{active:requestBox==='INCOMING'}" @click="requestBox='INCOMING';loadRequests()">收到的</button><button :class="{active:requestBox==='OUTGOING'}" @click="requestBox='OUTGOING';loadRequests()">发出的</button></div>
        <select v-model="requestStatus" @change="loadRequests"><option value="PENDING">待处理</option><option value="ACCEPTED">已接受</option><option value="REJECTED">已拒绝</option><option value="WITHDRAWN">已撤回</option></select>
      </div>
      <div v-if="loading" class="list-loading">正在加载申请…</div>
      <div v-else-if="requests.length" class="people-list">
        <article v-for="request in requests" :key="request.id" class="person-card">
          <UserAvatar :profile="requestBox === 'INCOMING' ? request.requester : request.recipient" :size="50" />
          <div class="person-info"><strong>{{ (requestBox === 'INCOMING' ? request.requester : request.recipient).nickname }}</strong><span>@{{ (requestBox === 'INCOMING' ? request.requester : request.recipient).username }}</span><p>{{ formatDate(request.createdAt) }} · {{ request.status }}</p></div>
          <div v-if="request.status === 'PENDING'" class="row-actions">
            <template v-if="requestBox === 'INCOMING'"><button class="small-button primary-small" @click="handleRequest(request, 'accept')"><UiIcon name="check" :size="15" /> 接受</button><button class="small-button" @click="handleRequest(request, 'reject')">拒绝</button></template>
            <button v-else class="small-button" @click="handleRequest(request, 'withdraw')">撤回</button>
          </div>
          <span v-else class="status-pill">{{ request.status }}</span>
        </article>
      </div>
      <div v-else class="empty-state compact-empty"><div class="empty-illustration"><UiIcon name="people" :size="30" /></div><h2>没有相关申请</h2><p>新的好友申请会出现在这里。</p></div>
    </section>

    <section v-else class="panel-section">
      <div class="section-title"><div><h2>黑名单</h2><p>这些用户无法与你建立好友关系。</p></div></div>
      <div v-if="loading" class="list-loading">正在加载…</div>
      <div v-else-if="blocked.length" class="people-list">
        <article v-for="user in blocked" :key="user.id" class="person-card subdued"><UserAvatar :profile="user" :size="50" /><div class="person-info"><strong>{{ user.nickname }}</strong><span>@{{ user.username }}</span></div><button class="small-button" @click="unblock(user)">取消拉黑</button></article>
      </div>
      <div v-else class="empty-state compact-empty"><div class="empty-illustration"><UiIcon name="shield" :size="30" /></div><h2>黑名单为空</h2><p>你没有拉黑任何人。</p></div>
    </section>
  </div>
</template>
