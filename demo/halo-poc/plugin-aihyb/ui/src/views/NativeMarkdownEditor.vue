<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { coreApiClient, axiosInstance } from '@halo-dev/api-client'
const props = defineProps<{ raw?: string; content?: string; title?: string; cover?: string }>()
const emit = defineEmits(['update:raw', 'update:content', 'update:title', 'update'])
const contentId = ref('')
const workflowError = ref('')
const archived = ref(false)
const postName = new URLSearchParams(window.location.search).get('name') || ''
const linkedSave = new Map<string, { previous: number; next: number }>()
const metaPath = `/apis/content.halo.run/v1alpha1/posts/${postName}`
const contentPath = `/apis/api.console.halo.run/v1alpha1/posts/${postName}/content`
const publishPath = `/apis/api.console.halo.run/v1alpha1/posts/${postName}/publish`
let savedHead = ''
let savedEpoch = '0'
// This bridge is scoped to the mounted native editor. It never fetches a fresh
// token before a write: doing so would overwrite another editor's changes.
const responseInterceptor = axiosInstance.interceptors.response.use(response => {
  if (response.config.method?.toLowerCase() === 'put' && [metaPath, contentPath].includes(response.config.url || '')) {
    workflowError.value = ''
    savedHead = response.data?.spec?.headSnapshot || ''
    savedEpoch = response.data?.metadata?.annotations?.['aihyb.run/operation-epoch'] || '0'
  }
  if (response.config.method?.toLowerCase() === 'put' && response.config.url === metaPath) {
    const input = typeof response.config.data === 'string' ? JSON.parse(response.config.data) : response.config.data
    const previous = Number(input?.metadata?.annotations?.['aihyb.run/editor-version'])
    const next = Number(response.data?.metadata?.annotations?.['aihyb.run/editor-version'])
    if (Number.isSafeInteger(previous) && Number.isSafeInteger(next) && previous > 0 && next > 0) {
      linkedSave.set(postName, { previous, next })
    }
  }
  return response
}, error => {
  if ([metaPath, contentPath, publishPath].includes(error.config?.url || '')) {
    workflowError.value = error.response?.data?.message || error.message || '操作失败，请重新加载并核对版本'
  }
  return Promise.reject(error)
})
const requestInterceptor = axiosInstance.interceptors.request.use(config => {
  if (config.method?.toLowerCase() === 'put' && config.url === publishPath && postName.startsWith('ap-')) {
    if (!savedHead) throw new Error('请先保存当前稿件，再发布')
    config.params = { ...config.params, headSnapshot: savedHead, operationEpoch: savedEpoch }
  }
  if (config.method?.toLowerCase() === 'put' && config.url === contentPath && postName.startsWith('ap-')) {
    const input = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
    const linked = linkedSave.get(postName)
    linkedSave.delete(postName)
    if (linked && input.version === linked.previous) input.version = linked.next
    // The server owns rendering; omit the potentially much larger local HTML.
    input.content = ''
    config.data = input
  }
  return config
})
onUnmounted(() => { axiosInstance.interceptors.request.eject(requestInterceptor); axiosInstance.interceptors.response.eject(responseInterceptor) })
function updateRaw(value: string) {
  emit('update:raw', value)
  // Only a safe local placeholder. Server CommonMark rendering is authoritative.
  emit('update:content', '<pre>' + value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;') + '</pre>')
  emit('update')
}
function updateTitle(value: string) { emit('update:title', value); emit('update') }
onMounted(async () => {
  if (!postName) return
  const result = await coreApiClient.content.post.getPost({ name: postName })
  contentId.value = result.data.metadata.annotations?.['aihyb.run/content-id'] || ''
  archived.value = result.data.metadata.annotations?.['aihyb.run/archived'] === 'true'
  savedEpoch = result.data.metadata.annotations?.['aihyb.run/operation-epoch'] || '0'
})
</script>
<template>
  <div class="native-markdown-editor">
    <p>使用上方 Halo 保存、预览和发布按钮。先保存文字，再核对稿件数据并审核；未审核的修订无法发布。</p>
    <p v-if="workflowError" role="alert" class="workflow-error">{{ workflowError }}</p>
    <p v-if="archived" class="workflow-error">稿件已归档。请从稿件数据入口取消归档后编辑；取消归档不会自动公开。</p>
    <a v-if="contentId" :href="`/console/aihyb-packages?content=${encodeURIComponent(contentId)}&post=${encodeURIComponent(postName)}`">稿件数据与审核 →</a>
    <FormKit type="text" label="标题" :disabled="archived" :model-value="props.title" @update:model-value="updateTitle" />
    <FormKit type="textarea" label="正文（Markdown）" :disabled="archived" :model-value="props.raw" :rows="22" @update:model-value="updateRaw" />
  </div>
</template>
<style scoped>
.native-markdown-editor{height:100%;overflow:auto;max-width:1100px;margin:auto;padding:24px}.native-markdown-editor p{color:#64748b}.native-markdown-editor a{display:inline-block;margin-bottom:18px;color:#2454c6}
.native-markdown-editor .workflow-error{padding:12px;background:#fff0c6;color:#9a3b12;border-radius:8px}
</style>
