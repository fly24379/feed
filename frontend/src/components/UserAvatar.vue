<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  profile: { type: Object, default: null },
  size: { type: Number, default: 44 },
})
const failed = ref(false)
const initials = computed(() => (props.profile?.nickname || props.profile?.username || '?').slice(0, 2).toUpperCase())
</script>

<template>
  <span class="avatar" :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.max(12, size * .34)}px` }">
    <img v-if="profile?.avatarUrl && !failed" :src="profile.avatarUrl" :alt="`${profile.nickname} 的头像`" @error="failed = true">
    <span v-else>{{ initials }}</span>
  </span>
</template>
