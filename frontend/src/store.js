import { reactive } from 'vue'
import { endpoints, session } from './api'

export const store = reactive({
  ready: false,
  user: null,
  claims: {},
  unreadCount: 0,
  toast: null,
  profileCache: new Map(),
  mediaUrls: new Map(),
})

let toastTimer

export function notify(message, tone = 'success') {
  store.toast = { message, tone, id: Date.now() }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { store.toast = null }, 3600)
}

export function setSession(access) {
  session.setTokens(access)
  store.claims = session.claims()
}

export function clearSession() {
  session.clear()
  store.user = null
  store.claims = {}
  store.unreadCount = 0
  store.profileCache.clear()
  for (const url of store.mediaUrls.values()) URL.revokeObjectURL(url)
  store.mediaUrls.clear()
}

export async function bootstrapSession() {
  store.claims = session.claims()
  if (!session.token && !session.refreshToken) {
    clearSession()
    store.ready = true
    return
  }
  try {
    store.user = await endpoints.me()
    store.profileCache.set(store.user.id, store.user)
    await refreshUnread()
  } catch {
    clearSession()
  } finally {
    store.ready = true
  }
}

export async function refreshUnread() {
  if (!store.user) return
  try {
    const page = await endpoints.notifications(true, null, 1)
    store.unreadCount = page.unreadCount
  } catch {
    // Notification badge is non-critical; page actions still surface errors.
  }
}

export async function getProfile(userId) {
  if (store.profileCache.has(userId)) return store.profileCache.get(userId)
  const profile = await endpoints.user(userId)
  store.profileCache.set(userId, profile)
  return profile
}

export async function getMediaUrl(mediaId) {
  if (store.mediaUrls.has(mediaId)) return store.mediaUrls.get(mediaId)
  const blob = await endpoints.mediaBlob(mediaId)
  const url = URL.createObjectURL(blob)
  store.mediaUrls.set(mediaId, url)
  return url
}

export function isAdmin() {
  return Array.isArray(store.claims.roles) && store.claims.roles.includes('ADMIN')
}
