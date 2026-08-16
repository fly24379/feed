<script setup>
import { onMounted, ref, watch } from 'vue'
import { getMediaUrl } from '../store'
import UiIcon from './UiIcon.vue'

const props = defineProps({ attachments: { type: Array, default: () => [] } })
const originals = ref({})
const previews = ref({})
const failed = ref({})

async function hydrate() {
  await Promise.all(props.attachments.map(async (item) => {
    if (originals.value[item.id]) return
    try {
      originals.value[item.id] = await getMediaUrl(item.id, 'ORIGINAL')
      if (item.previewStatus === 'READY') {
        try { previews.value[item.id] = await getMediaUrl(item.id, 'PREVIEW') } catch { /* original is enough */ }
      }
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
      <div v-else-if="!originals[item.id]" class="media-skeleton"></div>
      <video v-else-if="item.mediaType === 'VIDEO'" :src="originals[item.id]"
             :poster="previews[item.id]" controls preload="metadata"></video>
      <img v-else :src="previews[item.id] || originals[item.id]" :alt="item.originalFilename" loading="lazy">
    </div>
  </div>
</template>
