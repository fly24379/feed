<script setup>
import { computed, ref } from 'vue'
import { endpoints } from '../api'
import { notify } from '../store'
import MediaGallery from './MediaGallery.vue'
import UiIcon from './UiIcon.vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  post: { type: Object, required: true },
  social: { type: Object, required: true },
  author: { type: Object, default: null },
  currentUserId: { type: Number, required: true },
})
const emit = defineEmits(['social-change', 'deleted'])
const liking = ref(false)
const commentsOpen = ref(false)
const commentsLoading = ref(false)
const comments = ref([])
const commentText = ref('')
const submittingComment = ref(false)

const visibilityLabel = computed(() => ({
  ALL_FOLLOWERS: '粉丝可见', ALL_FRIENDS: '粉丝可见', ONLY_ME: '仅自己',
  INCLUDE_LIST: '部分粉丝', EXCLUDE_LIST: '部分粉丝不可见',
})[props.post.visibility] || props.post.visibility)

function relativeTime(value) {
  const time = new Date(value)
  const seconds = Math.max(1, Math.round((Date.now() - time.getTime()) / 1000))
  if (seconds < 60) return '刚刚'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`
  if (seconds < 604800) return `${Math.floor(seconds / 86400)} 天前`
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(time)
}

async function toggleLike() {
  if (liking.value) return
  liking.value = true
  try {
    const result = props.social.likedByMe
      ? await endpoints.unlike(props.post.id)
      : await endpoints.like(props.post.id)
    emit('social-change', { ...props.social, likeCount: result.likeCount, likedByMe: result.likedByMe })
  } catch (error) {
    notify(error.message, 'error')
  } finally {
    liking.value = false
  }
}

async function toggleComments() {
  commentsOpen.value = !commentsOpen.value
  if (!commentsOpen.value || comments.value.length || commentsLoading.value) return
  commentsLoading.value = true
  try {
    const page = await endpoints.comments(props.post.id)
    comments.value = page.items
  } catch (error) {
    notify(error.message, 'error')
  } finally {
    commentsLoading.value = false
  }
}

async function addComment() {
  const content = commentText.value.trim()
  if (!content || submittingComment.value) return
  submittingComment.value = true
  try {
    const created = await endpoints.comment(props.post.id, content)
    comments.value.push(created)
    commentText.value = ''
    emit('social-change', { ...props.social, commentCount: props.social.commentCount + 1 })
  } catch (error) {
    notify(error.message, 'error')
  } finally {
    submittingComment.value = false
  }
}

async function removeComment(comment) {
  if (!window.confirm('删除这条评论？')) return
  try {
    await endpoints.deleteComment(comment.id)
    comments.value = comments.value.filter((item) => item.id !== comment.id)
    emit('social-change', { ...props.social, commentCount: Math.max(0, props.social.commentCount - 1) })
  } catch (error) {
    notify(error.message, 'error')
  }
}

async function removePost() {
  if (!window.confirm('删除这条动态？删除后粉丝将无法再看到它。')) return
  try {
    await endpoints.deletePost(props.post.id)
    emit('deleted', props.post.id)
    notify('动态已删除')
  } catch (error) {
    notify(error.message, 'error')
  }
}
</script>

<template>
  <article class="post-card">
    <header class="post-header">
      <UserAvatar :profile="author" :size="46" />
      <div class="post-author min-w-0">
        <strong>{{ author?.nickname || `用户 ${post.authorId}` }}</strong>
        <div><span v-if="author">@{{ author.username }}</span><span>{{ relativeTime(post.publishedAt) }}</span><span>{{ visibilityLabel }}</span></div>
      </div>
      <button v-if="post.authorId === currentUserId" class="icon-button quiet danger-hover" type="button"
              title="删除动态" @click="removePost"><UiIcon name="trash" :size="18" /></button>
    </header>

    <p class="post-content">{{ post.content }}</p>
    <MediaGallery :attachments="social.attachments || []" />

    <footer class="post-actions">
      <button type="button" :class="['action-button', { liked: social.likedByMe }]" :disabled="liking" @click="toggleLike">
        <UiIcon name="heart" :size="19" /> <span>{{ social.likeCount || '点赞' }}</span>
      </button>
      <button type="button" :class="['action-button', { active: commentsOpen }]" @click="toggleComments">
        <UiIcon name="comment" :size="19" /> <span>{{ social.commentCount || '评论' }}</span>
      </button>
    </footer>

    <section v-if="commentsOpen" class="comments-panel">
      <div v-if="commentsLoading" class="inline-loading">正在加载评论…</div>
      <div v-else-if="!comments.length" class="empty-inline">还没有评论，来说第一句吧。</div>
      <div v-for="comment in comments" :key="comment.id" class="comment-row">
        <UserAvatar :profile="comment.author" :size="32" />
        <div class="comment-bubble">
          <div><strong>{{ comment.author.nickname }}</strong><span>{{ relativeTime(comment.createdAt) }}</span></div>
          <p>{{ comment.content }}</p>
        </div>
        <button v-if="comment.author.id === currentUserId || post.authorId === currentUserId"
                class="icon-button quiet tiny" type="button" title="删除评论" @click="removeComment(comment)">
          <UiIcon name="close" :size="14" />
        </button>
      </div>
      <form class="comment-form" @submit.prevent="addComment">
        <UserAvatar :profile="{ nickname: '我' }" :size="32" />
        <input v-model="commentText" maxlength="1000" placeholder="写下你的评论…" aria-label="评论内容">
        <button class="icon-button primary-icon" type="submit" :disabled="!commentText.trim() || submittingComment" title="发表评论">
          <UiIcon name="send" :size="17" />
        </button>
      </form>
    </section>
  </article>
</template>
