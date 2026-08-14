<script setup>
import { reactive, ref, watchEffect } from 'vue'
import { endpoints } from '../api'
import { notify, store } from '../store'
import UiIcon from '../components/UiIcon.vue'
import UserAvatar from '../components/UserAvatar.vue'

const saving = ref(false)
const form = reactive({ nickname: '', bio: '', avatarUrl: '' })

watchEffect(() => {
  if (!store.user) return
  form.nickname = store.user.nickname || ''
  form.bio = store.user.bio || ''
  form.avatarUrl = store.user.avatarUrl || ''
})

async function save() {
  saving.value = true
  try {
    const updated = await endpoints.updateMe({
      nickname: form.nickname.trim(), bio: form.bio.trim(), avatarUrl: form.avatarUrl.trim(),
    })
    store.user = updated
    store.profileCache.set(updated.id, updated)
    notify('个人资料已更新')
  } catch (error) { notify(error.message, 'error') }
  finally { saving.value = false }
}
</script>

<template>
  <div class="single-page profile-page">
    <header class="page-heading"><div><p class="eyebrow">PROFILE</p><h1>我的资料</h1><p>让朋友更容易认出你。</p></div></header>
    <div class="profile-grid">
      <aside class="profile-preview card-surface">
        <div class="profile-cover"><span></span><span></span></div>
        <UserAvatar :profile="{ ...store.user, nickname: form.nickname, avatarUrl: form.avatarUrl }" :size="88" />
        <h2>{{ form.nickname || store.user.nickname }}</h2><p class="username">@{{ store.user.username }}</p>
        <p class="profile-bio">{{ form.bio || '写一段介绍，让朋友更了解你。' }}</p>
        <div class="profile-trust"><UiIcon name="shield" :size="17" /> 已通过 JWT 身份认证</div>
      </aside>

      <section class="profile-form-card card-surface">
        <div class="section-title"><div><h2>编辑资料</h2><p>用户名注册后不可修改。</p></div></div>
        <form class="stack-form profile-form" @submit.prevent="save">
          <label><span>用户名</span><input :value="store.user.username" disabled><small>用于登录和被其他用户搜索</small></label>
          <label><span>昵称</span><input v-model="form.nickname" maxlength="80" required placeholder="你的昵称"><small>{{ form.nickname.length }}/80</small></label>
          <label><span>个人简介</span><textarea v-model="form.bio" rows="5" maxlength="500" placeholder="介绍一下自己…"></textarea><small>{{ form.bio.length }}/500</small></label>
          <label><span>头像 URL</span><input v-model="form.avatarUrl" maxlength="500" type="url" placeholder="https://example.com/avatar.jpg"><small>留空将使用昵称首字作为头像</small></label>
          <div class="form-actions"><button class="primary-button" type="submit" :disabled="saving || !form.nickname.trim()">{{ saving ? '保存中…' : '保存修改' }}</button></div>
        </form>
      </section>
    </div>
  </div>
</template>
