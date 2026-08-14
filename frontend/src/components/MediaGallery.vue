<script setup>
import { onMounted, ref, watch } from 'vue'
import { getMediaUrl } from '../store'
import UiIcon from './UiIcon.vue'

const props = defineProps({ attachments: { type: Array, default: () => [] } })
const sources = ref({})
const failed = ref({})

async function hydrate() {
  await Promise.all(props.attachments.map(async (item) => {
    if (sources.value[item.id]) return
    try {
      sources.value[item.id] = await getMediaUrl(item.id)
    } catch {
      failed.value[item.id] = true
    }
  }))
}

onMounted(hydrate)
watch(() => props.attachments, hydrate, { deep: true })
</script>

<template>
  <div v-if="attachments.length" :class="['media-grid', `media-count-${Math.min(attachments.length, 4)}`]">
    <div v-for="item in attachments" :key="item.id" class="media-cell">
      <div v-if="failed[item.id]" class="media-fallback"><UiIcon name="image" /> 无法加载附件</div>
      <div v-else-if="!sources[item.id]" class="media-skeleton"></div>
      <video v-else-if="item.mediaType === 'VIDEO'" :src="sources[item.id]" controls preload="metadata"></video>
      <img v-else :src="sources[item.id]" :alt="item.originalFilename" loading="lazy">
    </div>
  </div>
</template>
