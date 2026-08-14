<script setup>
import { reactive, ref } from 'vue'
import { endpoints } from '../api'
import UiIcon from '../components/UiIcon.vue'

const emit = defineEmits(['authenticated'])
const mode = ref('login')
const loading = ref(false)
const error = ref('')
const form = reactive({ username: '', nickname: '', password: '' })

async function submit() {
  error.value = ''
  loading.value = true
  try {
    const result = mode.value === 'login'
      ? await endpoints.login({ username: form.username, password: form.password })
      : await endpoints.register({ username: form.username, nickname: form.nickname, password: form.password })
    emit('authenticated', result)
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

function switchMode(next) {
  mode.value = next
  error.value = ''
}
</script>

<template>
  <div class="auth-page">
    <section class="auth-story">
      <div class="story-inner">
        <div class="story-brand"><span class="brand-mark light">F</span><strong>Friend Feed</strong></div>
        <p class="eyebrow light-text">A QUIETER SOCIAL SPACE</p>
        <h1>把生活，分享给<br><em>真正关心</em>的人。</h1>
        <p class="story-copy">没有陌生流量，没有算法喧嚣。只看见朋友此刻真实的生活。</p>
        <div class="orbit-art" aria-hidden="true">
          <span class="orbit orbit-one"></span><span class="orbit orbit-two"></span>
          <span class="portrait-dot dot-a">A</span><span class="portrait-dot dot-b">M</span>
          <span class="portrait-dot dot-c">林</span><span class="portrait-dot dot-d">K</span>
          <span class="orbit-heart">♡</span>
        </div>
        <div class="trust-line"><UiIcon name="lock" :size="17" /> 动态权限每次读取实时校验</div>
      </div>
    </section>

    <section class="auth-panel">
      <div class="auth-card">
        <div class="mobile-brand"><span class="brand-mark">F</span><strong>Friend Feed</strong></div>
        <p class="eyebrow">WELCOME</p>
        <h2>{{ mode === 'login' ? '欢迎回来' : '创建你的朋友圈' }}</h2>
        <p class="muted">{{ mode === 'login' ? '登录后看看朋友们的最近动态。' : '只需几步，就可以开始分享。' }}</p>

        <div class="auth-tabs" role="tablist">
          <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
          <button type="button" :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
        </div>

        <form class="stack-form" @submit.prevent="submit">
          <label><span>用户名</span><input v-model.trim="form.username" autocomplete="username"
            pattern="[A-Za-z0-9_]{3,32}" maxlength="32" required placeholder="例如 alice_01"></label>
          <label v-if="mode === 'register'"><span>昵称</span><input v-model.trim="form.nickname"
            autocomplete="nickname" maxlength="80" required placeholder="朋友们看到的名字"></label>
          <label><span>密码</span><input v-model="form.password" type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            minlength="8" maxlength="72" required placeholder="至少 8 位字符"></label>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="primary-button auth-submit" type="submit" :disabled="loading">
            <span>{{ loading ? '请稍候…' : (mode === 'login' ? '进入 Friend Feed' : '注册并进入') }}</span>
            <UiIcon v-if="!loading" name="chevron" :size="19" />
          </button>
        </form>
        <p class="auth-note">继续即表示你理解：这里的内容只对符合动态权限的人可见。</p>
      </div>
    </section>
  </div>
</template>
