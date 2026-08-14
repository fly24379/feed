const TOKEN_KEY = 'friend-feed.access-token'

export class ApiError extends Error {
  constructor(status, message, payload = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.payload = payload
  }
}

export const session = {
  get token() {
    return localStorage.getItem(TOKEN_KEY)
  },
  set token(value) {
    if (value) localStorage.setItem(TOKEN_KEY, value)
    else localStorage.removeItem(TOKEN_KEY)
  },
  claims() {
    const token = this.token
    if (!token) return {}
    try {
      const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
      return JSON.parse(decodeURIComponent(atob(payload).split('').map((char) =>
        `%${char.charCodeAt(0).toString(16).padStart(2, '0')}`).join('')))
    } catch {
      return {}
    }
  },
  clear() {
    this.token = null
  },
}

export async function api(path, options = {}) {
  const headers = new Headers(options.headers || {})
  headers.set('Accept', options.responseType === 'blob' ? '*/*' : 'application/json')
  if (session.token) headers.set('Authorization', `Bearer ${session.token}`)

  let body = options.body
  if (body != null && !(body instanceof FormData) && typeof body !== 'string') {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(body)
  }

  let response
  try {
    response = await fetch(path, { ...options, headers, body })
  } catch {
    throw new ApiError(0, '无法连接服务器，请检查服务是否已启动')
  }

  if (!response.ok) {
    const contentType = response.headers.get('content-type') || ''
    let payload = null
    try {
      payload = contentType.includes('json') ? await response.json() : await response.text()
    } catch {
      payload = null
    }
    const message = payload?.detail || payload?.message || (typeof payload === 'string' && payload)
      || `请求失败（${response.status}）`
    if (response.status === 401 && !path.startsWith('/api/auth/')) {
      session.clear()
      window.dispatchEvent(new CustomEvent('session-expired'))
    }
    throw new ApiError(response.status, message, payload)
  }

  if (response.status === 204) return null
  if (options.responseType === 'blob') return response.blob()
  const contentType = response.headers.get('content-type') || ''
  return contentType.includes('json') ? response.json() : response.text()
}

export const endpoints = {
  login: (body) => api('/api/auth/login', { method: 'POST', body }),
  register: (body) => api('/api/auth/register', { method: 'POST', body }),
  me: () => api('/api/users/me'),
  updateMe: (body) => api('/api/users/me', { method: 'PATCH', body }),
  user: (id) => api(`/api/users/${id}`),
  searchUsers: (query, afterId = null, size = 20) => {
    const params = new URLSearchParams({ q: query, size })
    if (afterId != null) params.set('afterId', afterId)
    return api(`/api/users/search?${params}`)
  },
  feed: (cursor = null, size = 10) => {
    const params = new URLSearchParams({ size })
    if (cursor) params.set('cursor', cursor)
    return api(`/api/feed?${params}`)
  },
  post: (id) => api(`/api/posts/${id}`),
  publish: (body, key) => api('/api/posts', {
    method: 'POST', body, headers: { 'Idempotency-Key': key },
  }),
  deletePost: (id) => api(`/api/posts/${id}`, { method: 'DELETE' }),
  like: (id) => api(`/api/posts/${id}/like`, { method: 'PUT' }),
  unlike: (id) => api(`/api/posts/${id}/like`, { method: 'DELETE' }),
  comments: (id, afterId = null, size = 50) => {
    const params = new URLSearchParams({ size })
    if (afterId != null) params.set('afterId', afterId)
    return api(`/api/posts/${id}/comments?${params}`)
  },
  comment: (id, content) => api(`/api/posts/${id}/comments`, { method: 'POST', body: { content } }),
  deleteComment: (id) => api(`/api/comments/${id}`, { method: 'DELETE' }),
  upload: (file) => {
    const data = new FormData()
    data.append('file', file)
    return api('/api/media', { method: 'POST', body: data })
  },
  deleteMedia: (id) => api(`/api/media/${id}`, { method: 'DELETE' }),
  mediaBlob: (id) => api(`/api/media/${id}/content`, { responseType: 'blob' }),
  friends: () => api('/api/relationships/friends'),
  blocks: () => api('/api/relationships/blocks'),
  friendRequests: (box = 'INCOMING', status = 'PENDING', beforeId = null, size = 50) => {
    const params = new URLSearchParams({ box, status, size })
    if (beforeId != null) params.set('beforeId', beforeId)
    return api(`/api/relationships/friend-requests?${params}`)
  },
  sendFriendRequest: (recipientId) => api('/api/relationships/friend-requests', {
    method: 'POST', body: { recipientId },
  }),
  acceptFriendRequest: (id) => api(`/api/relationships/friend-requests/${id}/accept`, { method: 'POST' }),
  rejectFriendRequest: (id) => api(`/api/relationships/friend-requests/${id}/reject`, { method: 'POST' }),
  withdrawFriendRequest: (id) => api(`/api/relationships/friend-requests/${id}`, { method: 'DELETE' }),
  removeFriend: (id) => api(`/api/relationships/friends/${id}`, { method: 'DELETE' }),
  block: (id) => api(`/api/relationships/blocks/${id}`, { method: 'PUT' }),
  unblock: (id) => api(`/api/relationships/blocks/${id}`, { method: 'DELETE' }),
  notifications: (unreadOnly = false, beforeId = null, size = 50) => {
    const params = new URLSearchParams({ unreadOnly, size })
    if (beforeId != null) params.set('beforeId', beforeId)
    return api(`/api/notifications?${params}`)
  },
  markNotificationRead: (id) => api(`/api/notifications/${id}/read`, { method: 'PATCH' }),
  markAllNotificationsRead: () => api('/api/notifications/read-all', { method: 'PATCH' }),
  outboxMetrics: () => api('/api/admin/outbox/metrics'),
  replayOutbox: (id) => api(`/api/admin/outbox/${id}/replay`, { method: 'POST' }),
}
