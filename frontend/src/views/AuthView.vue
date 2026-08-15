<script setup>
import { computed, reactive, ref } from 'vue'
import { endpoints } from '../api'
import UiIcon from '../components/UiIcon.vue'

const emit = defineEmits(['authenticated'])
const mode = ref('login')
const loading = ref(false)
const sendingCode = ref(false)
const error = ref('')
const notice = ref('')
const form = reactive({
  username: '', nickname: '', password: '', channel: 'EMAIL', target: '',
  challengeId: '', verificationCode: '',
})
const recovery = reactive({ account: '', challengeId: '', verificationCode: '', newPassword: '' })

const heading = computed(() => ({
  login: ['欢迎回来', '登录后看看朋友们的最近动态。'],
  register: ['创建你的朋友圈', '验证邮箱或手机后，就可以开始分享。'],
  recover: ['找回密码', '我们会向已验证的邮箱或手机发送验证码。'],
}[mode.value]))

async function submit() {
  error.value = ''
  notice.value = ''
  loading.value = true
  try {
    const result = mode.value === 'login'
      ? await endpoints.login({ username: form.username, password: form.password })
      : await endpoints.register({
        username: form.username, nickname: form.nickname, password: form.password,
        channel: form.channel, target: form.target, challengeId: form.challengeId,
        verificationCode: form.verificationCode,
      })
    emit('authenticated', result)
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

async function sendRegistrationCode() {
  error.value = ''
  notice.value = ''
  sendingCode.value = true
  try {
    const result = await endpoints.requestRegistrationCode({ channel: form.channel, target: form.target })
    form.challengeId = result.challengeId
    notice.value = '验证码已发送，约 ' + Math.max(1, Math.ceil(result.expiresIn / 60)) + ' 分钟内有效。'
  } catch (exception) {
    error.value = exception.message
  } finally {
    sendingCode.value = false
  }
}

async function requestReset() {
  error.value = ''
  notice.value = ''
  sendingCode.value = true
  try {
    const result = await endpoints.requestPasswordReset({ account: recovery.account })
    recovery.challengeId = result.challengeId
    notice.value = '如果账号存在，验证码已发送到已验证的联系方式。'
  } catch (exception) {
    error.value = exception.message
  } finally {
    sendingCode.value = false
  }
}

async function confirmReset() {
  error.value = ''
  loading.value = true
  try {
    await endpoints.confirmPasswordReset({
      challengeId: recovery.challengeId,
      verificationCode: recovery.verificationCode,
      newPassword: recovery.newPassword,
    })
    switchMode('login')
    notice.value = '密码已更新，请使用新密码登录。'
    recovery.verificationCode = ''
    recovery.newPassword = ''
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

function switchMode(next) {
  mode.value = next
  error.value = ''
  notice.value = ''
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
        <h2>{{ heading[0] }}</h2>
        <p class="muted">{{ heading[1] }}</p>

        <div v-if="mode !== 'recover'" class="auth-tabs" role="tablist">
          <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
          <button type="button" :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
        </div>

        <form v-if="mode !== 'recover'" class="stack-form" @submit.prevent="submit">
          <label><span>用户名</span><input v-model.trim="form.username" autocomplete="username"
            pattern="[A-Za-z0-9_]{3,32}" maxlength="32" required placeholder="例如 alice_01"></label>
          <label v-if="mode === 'register'"><span>昵称</span><input v-model.trim="form.nickname"
            autocomplete="nickname" maxlength="80" required placeholder="朋友们看到的名字"></label>
          <label><span>密码</span><input v-model="form.password" type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            minlength="8" maxlength="72" required placeholder="至少 8 位字符"></label>

          <template v-if="mode === 'register'">
            <div class="contact-row">
              <label><span>验证方式</span><select v-model="form.channel" @change="form.target = ''; form.challengeId = ''">
                <option value="EMAIL">邮箱</option><option value="PHONE">手机</option>
              </select></label>
              <label><span>{{ form.channel === 'EMAIL' ? '邮箱地址' : '手机号码' }}</span>
                <input v-model.trim="form.target" :type="form.channel === 'EMAIL' ? 'email' : 'tel'"
                  maxlength="254" required
                  :placeholder="form.channel === 'EMAIL' ? 'name@example.com' : '+8613812345678'">
              </label>
            </div>
            <div class="code-row">
              <label><span>验证码</span><input v-model.trim="form.verificationCode" inputmode="numeric"
                pattern="\d{6}" maxlength="6" required placeholder="6 位验证码"></label>
              <button class="secondary-button code-button" type="button"
                :disabled="sendingCode || !form.target" @click="sendRegistrationCode">
                {{ sendingCode ? '发送中…' : (form.challengeId ? '重新发送' : '获取验证码') }}
              </button>
            </div>
          </template>

          <p v-if="notice" class="form-success" role="status">{{ notice }}</p>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="primary-button auth-submit" type="submit"
            :disabled="loading || (mode === 'register' && !form.challengeId)">
            <span>{{ loading ? '请稍候…' : (mode === 'login' ? '进入 Friend Feed' : '注册并进入') }}</span>
            <UiIcon v-if="!loading" name="chevron" :size="19" />
          </button>
          <button v-if="mode === 'login'" class="text-button" type="button" @click="switchMode('recover')">
            忘记密码？
          </button>
        </form>

        <form v-else class="stack-form" @submit.prevent="recovery.challengeId ? confirmReset() : requestReset()">
          <label><span>用户名、邮箱或手机号</span><input v-model.trim="recovery.account"
            autocomplete="username" maxlength="254" required placeholder="用于定位你的账号"></label>
          <template v-if="recovery.challengeId">
            <label><span>验证码</span><input v-model.trim="recovery.verificationCode" inputmode="numeric"
              autocomplete="one-time-code" pattern="\d{6}" maxlength="6" required placeholder="6 位验证码"></label>
            <label><span>新密码</span><input v-model="recovery.newPassword" type="password"
              autocomplete="new-password" minlength="8" maxlength="72" required placeholder="至少 8 位字符"></label>
          </template>
          <p v-if="notice" class="form-success" role="status">{{ notice }}</p>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="primary-button auth-submit" type="submit" :disabled="loading || sendingCode">
            <span>{{ loading || sendingCode ? '请稍候…' : (recovery.challengeId ? '设置新密码' : '发送验证码') }}</span>
            <UiIcon v-if="!loading && !sendingCode" name="chevron" :size="19" />
          </button>
          <button v-if="recovery.challengeId" class="text-button" type="button" :disabled="sendingCode" @click="requestReset">
            重新发送验证码
          </button>
          <button class="text-button" type="button" @click="switchMode('login')">返回登录</button>
        </form>
        <p class="auth-note">验证码只用于确认账号归属；密码重置后，其他设备上的登录会立即失效。</p>
      </div>
    </section>
  </div>
</template>
