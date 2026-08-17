<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { endpoints } from '../api'
import { getProfile, notify, store } from '../store'
import PostCard from '../components/PostCard.vue'
import UiIcon from '../components/UiIcon.vue'
import UserAvatar from '../components/UserAvatar.vue'

const posts = ref([])
const socialByPost = reactive({})
const authors = reactive({})
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(true)
const loadingMore = ref(false)
const publishing = ref(false)
const content = ref('')
const visibility = ref('ALL_FOLLOWERS')
const selectedTargets = ref([])
const following = ref([])
const followers = ref([])
const selectedFiles = ref([])
const fileInput = ref(null)

const needsTargets = computed(() => ['INCLUDE_LIST', 'EXCLUDE_LIST'].includes(visibility.value))
const visibilityHint = computed(() => ({
  ALL_FOLLOWERS: '所有当前粉丝可见',
  ALL_FRIENDS: '所有当前粉丝可见',
  ONLY_ME: '只有你自己可见',
  INCLUDE_LIST: '仅勾选的粉丝可见',
  EXCLUDE_LIST: '除勾选的粉丝外可见',
})[visibility.value])

onMounted(async () => {
  await Promise.all([loadFeed(true), loadRelationships()])
})

onBeforeUnmount(() => selectedFiles.value.forEach((item) => URL.revokeObjectURL(item.preview)))

async function loadRelationships() {
  try {
    const [followingItems, followerItems] = await Promise.all([endpoints.following(), endpoints.followers()])
    following.value = followingItems.items
    followers.value = followerItems.items
  } catch {
    following.value = []
    followers.value = []
  }
}

async function loadFeed(reset = false) {
  if (reset) loading.value = true
  else loadingMore.value = true
  try {
    const page = await endpoints.feed(reset ? null : cursor.value, 10)
    if (reset) {
      posts.value = page.items
      Object.keys(socialByPost).forEach((key) => delete socialByPost[key])
    } else posts.value.push(...page.items)
    Object.assign(socialByPost, page.socialByPostId || {})
    cursor.value = page.nextCursor
    hasMore.value = page.hasMore
    await Promise.all(page.items.map(async (post) => {
      try { authors[post.authorId] = await getProfile(post.authorId) } catch { /* fallback in card */ }
    }))
  } catch (error) {
    notify(error.message, 'error')
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function chooseFiles(event) {
  const files = Array.from(event.target.files || [])
  const remaining = 9 - selectedFiles.value.length
  if (files.length > remaining) notify('每条动态最多添加 9 个附件', 'error')
  for (const file of files.slice(0, remaining)) {
    if (!file.type.startsWith('image/') && !file.type.startsWith('video/')) {
      notify(`${file.name} 不是支持的图片或视频`, 'error')
      continue
    }
    selectedFiles.value.push({ file, preview: URL.createObjectURL(file), kind: file.type.startsWith('video/') ? 'VIDEO' : 'IMAGE' })
  }
  event.target.value = ''
}

function removeSelected(index) {
  URL.revokeObjectURL(selectedFiles.value[index].preview)
  selectedFiles.value.splice(index, 1)
}

async function publish() {
  const text = content.value.trim()
  if (!text || publishing.value) return
  publishing.value = true
  const uploaded = []
  try {
    for (const item of selectedFiles.value) uploaded.push(await endpoints.upload(item.file))
    await endpoints.publish({
      content: text,
      visibility: visibility.value,
      targetUserIds: needsTargets.value ? selectedTargets.value : [],
      mediaIds: uploaded.map((item) => item.id),
    }, crypto.randomUUID())
    content.value = ''
    visibility.value = 'ALL_FOLLOWERS'
    selectedTargets.value = []
    selectedFiles.value.forEach((item) => URL.revokeObjectURL(item.preview))
    selectedFiles.value = []
    notify('动态已发布，正在按混合策略分发给粉丝')
    await loadFeed(true)
  } catch (error) {
    await Promise.allSettled(uploaded.map((item) => endpoints.deleteMedia(item.id)))
    notify(error.message, 'error')
  } finally {
    publishing.value = false
  }
}

function updateSocial(postId, social) { socialByPost[postId] = social }
function removePost(postId) { posts.value = posts.value.filter((post) => post.id !== postId) }
</script>

<template>
  <div class="page-layout feed-layout">
    <section class="content-column">
      <header class="page-heading compact-mobile">
        <div><p class="eyebrow">YOUR FEED</p><h1>关注动态</h1></div>
        <button class="icon-button soft" type="button" title="刷新" @click="loadFeed(true)"><UiIcon name="refresh" /></button>
      </header>

      <section class="composer card-surface">
        <div class="composer-main">
          <UserAvatar :profile="store.user" :size="46" />
          <textarea v-model="content" maxlength="2000" rows="3" placeholder="分享此刻发生的事…" aria-label="动态内容"></textarea>
        </div>

        <div v-if="selectedFiles.length" class="upload-preview-grid">
          <div v-for="(item, index) in selectedFiles" :key="item.preview" class="upload-preview">
            <video v-if="item.kind === 'VIDEO'" :src="item.preview"></video>
            <img v-else :src="item.preview" :alt="item.file.name">
            <button type="button" title="移除附件" @click="removeSelected(index)"><UiIcon name="close" :size="14" /></button>
          </div>
        </div>

        <div v-if="needsTargets" class="target-picker">
          <p>{{ visibilityHint }} · 已选 {{ selectedTargets.length }} 人</p>
          <div v-if="followers.length" class="target-list">
            <label v-for="follower in followers" :key="follower.id">
              <input v-model="selectedTargets" type="checkbox" :value="follower.id">
              <UserAvatar :profile="follower" :size="27" /><span>{{ follower.nickname }}</span>
            </label>
          </div>
          <p v-else class="muted small">当前还没有粉丝可供选择。</p>
        </div>

        <footer class="composer-footer">
          <div class="composer-tools">
            <input ref="fileInput" class="visually-hidden" type="file" multiple
                   accept="image/jpeg,image/png,image/gif,image/webp,video/mp4,video/webm,video/quicktime" @change="chooseFiles">
            <button class="tool-button" type="button" @click="fileInput.click()"><UiIcon name="image" :size="18" /> 图片 / 视频</button>
            <select v-model="visibility" class="visibility-select" aria-label="可见范围">
              <option value="ALL_FOLLOWERS">粉丝可见</option><option value="ONLY_ME">仅自己</option>
              <option value="INCLUDE_LIST">指定粉丝</option><option value="EXCLUDE_LIST">排除粉丝</option>
            </select>
          </div>
          <button class="primary-button publish-button" type="button" :disabled="!content.trim() || publishing" @click="publish">
            {{ publishing ? '发布中…' : '发布' }} <UiIcon v-if="!publishing" name="send" :size="17" />
          </button>
        </footer>
      </section>

      <div v-if="loading" class="post-skeleton-stack"><div v-for="n in 3" :key="n" class="post-skeleton"></div></div>
      <div v-else-if="!posts.length" class="empty-state card-surface">
        <div class="empty-illustration"><UiIcon name="people" :size="34" /></div>
        <h2>关注流还很安静</h2><p>关注感兴趣的人或发布第一条动态，让这里热闹起来。</p>
      </div>
      <div v-else class="feed-stack">
        <PostCard v-for="post in posts" :key="post.id" :post="post"
                  :social="socialByPost[post.id] || { likeCount: 0, commentCount: 0, likedByMe: false, attachments: [] }"
                  :author="authors[post.authorId]" :current-user-id="store.user.id"
                  @social-change="updateSocial(post.id, $event)" @deleted="removePost" />
        <button v-if="hasMore" class="secondary-button load-more" type="button" :disabled="loadingMore" @click="loadFeed(false)">
          {{ loadingMore ? '正在加载…' : '加载更多动态' }}
        </button>
      </div>
    </section>

    <aside class="context-rail">
      <section class="rail-card circle-card">
        <p class="eyebrow">FOLLOWING</p><h3>你关注的人</h3>
        <div class="avatar-stack">
          <UserAvatar v-for="user in following.slice(0, 6)" :key="user.id" :profile="user" :size="36" />
          <span v-if="following.length > 6" class="avatar-more">+{{ following.length - 6 }}</span>
        </div>
        <p>{{ following.length ? `正在关注 ${following.length} 人，已有 ${followers.length} 位粉丝。` : '去关系页关注第一个人吧。' }}</p>
      </section>
      <section class="rail-card privacy-card">
        <span class="rail-icon"><UiIcon name="shield" /></span>
        <div><h3>实时关系校验</h3><p>取关或拉黑后，旧 Inbox 候选会在读取时立即失效。</p></div>
      </section>
      <p class="rail-footnote">Follow Feed · 混合扩散，稳定抵达</p>
    </aside>
  </div>
</template>
