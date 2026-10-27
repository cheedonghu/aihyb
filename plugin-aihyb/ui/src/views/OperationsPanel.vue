<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { utils } from '@halo-dev/ui-shared'
import { VButton, Dialog, Toast } from '@halo-dev/components'
import { mediaApi } from '@/api'
const props = defineProps<{ id: string; draftJson: string; draftRevision: string; dirty: boolean }>()
const emit = defineEmits<{ reload: []; archived: [value: boolean]; epoch: [value: string]; published: [value: boolean]; pending: [value: boolean] }>()
type State = { version: string; published: boolean; archived: boolean; draft_revision: string; operation_epoch: string }
type History = { revision: string; title: string; created_at: string; actor: string; draft: boolean; published: boolean; approved: boolean }
type Asset = { asset_id: string; object_key: string; content_type: string; sha256: string; alt: string; visibility: string }
const base = computed(() => `/apis/console.api.aihyb.run/v1alpha1/entries/${encodeURIComponent(props.id)}`)
const state = ref<State>()
const history = ref<History[]>([])
const historyPage = ref(1)
const historyTotal = ref(0)
const busy = ref(false)
const error = ref('')
const configured = ref(false)
const assets = ref<Asset[]>(JSON.parse(props.draftJson).assets || [])
const assetsDirty = ref(false)
const selectedRevision = ref('')
const canPublish = computed(() => utils.permission.has(['plugin:aihyb:publish']))
const canEdit = computed(() => utils.permission.has(['plugin:aihyb:view']))
const blocked = computed(() => busy.value || props.dirty)
function explain(e: unknown) { return (e as { response?: { data?: { message?: string } } }).response?.data?.message || '操作失败，请重新加载后核对状态。' }
async function reload() {
  const [status, versions, configuration] = await Promise.all([
    axiosInstance.get<State>(`${base.value}/operations`),
    axiosInstance.get<{ items: History[]; total: number }>(`${base.value}/history?page=${historyPage.value}&size=20`),
    mediaApi.mediaConfiguration(),
  ])
  state.value = status.data; emit('archived', status.data.archived); emit('epoch', status.data.operation_epoch);emit('published',status.data.published)
  history.value = versions.data.items; historyTotal.value = versions.data.total
  configured.value = configuration.data.configured === true
}
async function confirm(title: string, description: string) {
  return await new Promise<boolean>(resolve => Dialog.warning({ title, description, showCancel: true,
    confirmText: '确认', cancelText: '取消', onConfirm: () => resolve(true), onCancel: () => resolve(false) }))
}
async function operate(action: string) {
  if (!state.value || blocked.value || assetsDirty.value) return
  const labels: Record<string,string> = { withdraw: '撤稿', archive: '归档', unarchive: '取消归档' }
  if (!await confirm(labels[action] || '状态变更', action === 'unarchive' ? '取消归档后仍保持未发布，需要重新审核和发布。'
    : '文章将停止公开，完整稿件、附件与历史版本会保留。搜索和订阅随后同步，站点地图最多保留旧链接约30秒。')) return
  busy.value = true; error.value = ''
  try {
    await axiosInstance.post(`${base.value}/operations`, { version: state.value.version, action })
    await reload(); emit('reload'); Toast.success(`${labels[action]}完成`)
  } catch (e) { error.value = explain(e) }
  finally { busy.value = false }
}
async function restore() {
  if (!state.value || !selectedRevision.value || blocked.value || assetsDirty.value) return
  if (!await confirm('恢复为新草稿', '当前草稿会被选定历史内容替代，并保留在历史中；公开版本不变。新草稿需要重新审核。')) return
  busy.value = true; error.value = ''
  try {
    await axiosInstance.post(`${base.value}/restore`, { version: state.value.version, revision: selectedRevision.value })
    await reload(); emit('reload'); Toast.success('已恢复为新草稿，公开内容保持不变。')
  } catch (e) { error.value = explain(e) }
  finally { busy.value = false }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]
  if (!file || blocked.value || state.value?.archived) return
  if (file.size > 16 * 1024 * 1024) { error.value = 'PoC 单文件上限16 MiB；长视频请使用外部视频嵌入。'; input.value='';return }
  busy.value = true; error.value = ''
  emit('pending',true)
  try {
    const response = await mediaApi.uploadEntryAsset({ id: props.id, filename: file.name, body: file },
      { headers: { 'Content-Type': file.type || 'application/octet-stream' } })
    const { object_key, content_type, sha256, filename } = response.data
    if (!object_key || !content_type || !sha256) throw new Error('上传回执不完整')
    assets.value.push({ object_key, content_type, sha256,
      asset_id: `media-${crypto.randomUUID()}`, alt: filename || file.name, visibility: 'public' })
    assetsDirty.value = true
    emit('pending',true)
    Toast.success('文件已上传为私有对象，请保存附件到草稿后预览和审核。')
  } catch (e) { error.value = explain(e) }
  finally { busy.value = false; input.value = ''; emit('pending',assetsDirty.value) }
}
async function saveAssets() {
  if (blocked.value || state.value?.archived) return
  busy.value = true; error.value = ''
  try {
    const payload = JSON.parse(props.draftJson)
    // Bind to the package actually displayed in this window; never adopt a freshly fetched token.
    payload.base_revision = props.draftRevision
    payload.submission_id = `media-${crypto.randomUUID()}`; payload.assets = assets.value
    await axiosInstance.post('/apis/console.api.aihyb.run/v1alpha1/content-packages', payload)
    assetsDirty.value = false;emit('pending',false); emit('reload'); Toast.success('附件引用已保存为新草稿，尚未公开。')
  } catch (e) { error.value = explain(e) }
  finally { busy.value = false }
}
async function page(delta: number) { historyPage.value += delta; try { await reload() } catch (e) { error.value=explain(e) } }
function reference(asset: Asset) { return `${asset.content_type.startsWith('image/') ? '!' : ''}[${asset.alt.replaceAll(']', '')}](asset://${asset.asset_id})` }
function touchAssets(){assetsDirty.value=true;emit('pending',true)}
onMounted(async () => { try { await reload() } catch (e) { error.value=explain(e) } })
</script>
<template>
  <section class="operations">
    <h3>发布状态与历史</h3>
    <p v-if="state">{{ state.archived ? '已归档，暂停编辑和发布' : state.published ? '当前有公开版本' : '当前未公开' }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="buttons">
      <VButton v-if="canPublish && state?.published" :disabled="blocked || assetsDirty" @click="operate('withdraw')">撤稿</VButton>
      <VButton v-if="canPublish && !state?.archived" :disabled="blocked || assetsDirty" @click="operate('archive')">归档</VButton>
      <VButton v-if="canPublish && state?.archived" :disabled="blocked || assetsDirty" @click="operate('unarchive')">取消归档</VButton>
    </div>
    <label>历史版本<select v-model="selectedRevision"><option value="">请选择</option><option v-for="item in history" :key="item.revision" :value="item.revision">{{ item.created_at }} · {{ item.title }} {{ item.draft ? '（当前草稿）' : item.published ? '（上次发布）' : '' }}</option></select></label>
    <div class="buttons">
      <a v-if="selectedRevision" :href="`${base}/history/${selectedRevision}/preview`" target="_blank" rel="noopener noreferrer">预览选定历史 ↗</a>
      <VButton v-if="canEdit" :disabled="blocked || assetsDirty || !selectedRevision || state?.archived" @click="restore">恢复为新草稿</VButton>
      <VButton :disabled="blocked || historyPage <= 1" @click="page(-1)">上一页历史</VButton>
      <VButton :disabled="blocked || historyPage * 20 >= historyTotal" @click="page(1)">下一页历史</VButton>
    </div>
    <h3>媒体与附件</h3>
    <p v-if="!configured">尚未配置专用私有 S3 存储策略。配置完成后可上传图片、附件和短视频。</p>
    <p>公开附件随审核版本发布；“仅审核可见”的材料不会进入公开页面。删除引用会保留文件，以支持历史恢复。</p>
    <input v-if="canEdit" type="file" aria-label="上传媒体与附件" :disabled="blocked || !configured || state?.archived || assets.length >= 50" @change="upload">
    <fieldset v-for="(asset, index) in assets" :key="asset.asset_id" :disabled="blocked || state?.archived">
      <legend>{{ asset.content_type }}</legend>
      <label>说明<input v-model="asset.alt" maxlength="500" @input="touchAssets"></label>
      <label>可见范围<select v-model="asset.visibility" @change="touchAssets"><option value="public">随稿公开</option><option value="private">仅审核可见</option></select></label>
      <p v-if="asset.visibility === 'public'">正文引用：<code>{{ reference(asset) }}</code></p>
      <VButton v-if="canEdit" @click="assets.splice(index, 1); touchAssets()">移除本稿引用</VButton>
    </fieldset>
    <VButton v-if="canEdit" :disabled="blocked || !assetsDirty || state?.archived" @click="saveAssets">保存附件到草稿</VButton>
    <p>外部视频可在正文独占一行填写 <code>[video:bilibili:BV号]</code> 或 <code>[video:youtube:视频ID]</code>；发布前请预览并核对原始来源。</p>
  </section>
</template>
<style scoped>
.operations{margin:24px 0;padding:18px;border:1px solid #cbd5e1;border-radius:8px}.operations h3{margin:12px 0;font-weight:600}.operations p{margin:10px 0;color:#475569}.buttons{display:flex;flex-wrap:wrap;gap:12px;margin:12px 0}label{display:block;margin:10px 0}input,select{display:block;max-width:100%;padding:8px;border:1px solid #cbd5e1;border-radius:5px}fieldset{padding:14px;margin:12px 0;border:1px solid #ddd}.error{color:#b91c1c!important;white-space:pre-wrap}code{overflow-wrap:anywhere}a{color:#2454c6}
</style>
