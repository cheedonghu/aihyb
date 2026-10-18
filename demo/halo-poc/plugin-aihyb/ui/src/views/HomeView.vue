<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { VButton, VCard, VPageHeader, VLoading, Toast, Dialog } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { packageApi } from '@/api'
import type { EntryDetail, EntrySummary } from '@/api/generated'
import OperationsPanel from './OperationsPanel.vue'

const nativeContentId = new URLSearchParams(window.location.search).get('content') || ''
const nativePostName = new URLSearchParams(window.location.search).get('post') || ''
const nativePanel = Boolean(nativeContentId)
const items = ref<EntrySummary[]>([])
const detail = ref<EntryDetail>()
const defaultForm = ref<Record<string, unknown>>({})
const formValues = ref<Record<string, unknown>>({})
const busy = ref(false)
const archived = ref(false)
const operationEpoch = ref('0')
const currentlyPublished = ref(false)
const mediaPending = ref(false)
const message = ref('')
const showPreview = ref(false)
const formTouched = ref(false)
const page = ref(1)
const total = ref(0)
const filter = ref('')
const labels: Record<string, string> = {
  review: '能力测评', tutorial: '使用技巧与教程', leaderboard: '评分与榜单', pricing: '价格对比',
}
const current = computed(() => detail.value?.entry)
const raw = computed(() => detail.value?.draft_json ? JSON.parse(detail.value.draft_json) : undefined)
const visibleItems = computed(() => items.value)
const dirty = computed(() => formTouched.value || Object.keys({ ...defaultForm.value, ...formValues.value })
  .some(key => String(defaultForm.value[key] ?? '') !== String(formValues.value[key] ?? '')))
const approved = computed(() => current.value?.reviewed_revision === current.value?.draft_revision)
const canEdit = computed(() => !archived.value && utils.permission.has(['plugin:aihyb:view']))
const canReview = computed(() => !archived.value && utils.permission.has(['plugin:aihyb:review']))
const canPublish = computed(() => utils.permission.has(['plugin:aihyb:publish']))
const previewUrl = computed(() => current.value?.content_id
  ? `/apis/console.api.aihyb.run/v1alpha1/entries/${current.value.content_id}/preview?revision=${current.value.draft_revision}` : '')
const taskOptions = computed(() => Object.entries({ office: '综合办公', presentation: 'PPT 与演示', spreadsheet: '表格',
  image: '图像生成', video: '视频生成', writing: '写作', programming: '编程',
  ...(raw.value?.data.task && !['office', 'presentation', 'spreadsheet', 'image', 'video', 'writing', 'programming'].includes(raw.value.data.task)
    ? { [raw.value.data.task]: '其他（保留原分类）' } : {}),
}).map(([value, label]) => ({ value, label })))

function errorText(error: unknown) {
  const response = (error as { response?: { data?: { errors?: string[]; message?: string }; status?: number } }).response
  return response?.data?.errors?.join('\n') || response?.data?.message || '操作失败，请检查网络或重新加载当前草稿。'
}

async function loadList() {
  try {
    const result = await packageApi.listEntries({ page: page.value, size: 20, kind: filter.value || undefined })
    items.value = result.data.items || []
    total.value = result.data.total || 0
    message.value = ''
  } catch (error) { message.value = errorText(error) }
}

async function select(id: string | undefined) {
  if (!id || busy.value) return
  if ((dirty.value || mediaPending.value) && detail.value) {
    const discard = await new Promise<boolean>(resolve => Dialog.warning({
      title: '当前修改尚未保存', description: '放弃这些修改并加载另一份稿件？',
      confirmText: '放弃修改', cancelText: '继续编辑', showCancel: true,
      onConfirm: () => resolve(true), onCancel: () => resolve(false),
    }))
    if (!discard) return
  }
  busy.value = true
  message.value = ''
  try {
    const result = await packageApi.getEntry({ id })
    installDetail(result.data)
    showPreview.value = false
  } catch (error) { message.value = errorText(error) }
  finally { busy.value = false }
}

function installDetail(value: EntryDetail) {
  detail.value = value
  mediaPending.value = false
  const payload = JSON.parse(value.draft_json || '{}')
  const fields: Record<string, unknown> = {
    title: payload.content.title, summary: payload.content.summary,
    body: payload.content.body_markdown, task: payload.data.task,
  }
  if (payload.data.tool_version !== undefined) fields.tool_version = payload.data.tool_version
  if (payload.kind === 'review') payload.data.conclusions.forEach((item: { text: string }, index: number) => {
    fields[`conclusion_${index}`] = item.text
  })
  if (payload.kind === 'pricing') payload.data.plans.forEach((item: { amount: string | null; free_quota: { amount: string | null } }, index: number) => {
    fields[`amount_${index}`] = item.amount ?? ''
    fields[`quota_${index}`] = item.free_quota.amount ?? ''
  })
  if (payload.kind === 'leaderboard') payload.data.entries.forEach((item: { rank: number; score: string | null; reason: string }, index: number) => {
    fields[`rank_${index}`] = item.rank
    fields[`score_${index}`] = item.score ?? ''
    fields[`reason_${index}`] = item.reason
  })
  if (nativePanel) { delete fields.title; delete fields.summary; delete fields.body }
  defaultForm.value = fields
  formValues.value = { ...fields }
  formTouched.value = false
}

async function saveDraft(values: Record<string, unknown>) {
  if (!current.value?.content_id || busy.value || mediaPending.value || !detail.value?.draft_json) return
  busy.value = true
  message.value = ''
  try {
    const payload = JSON.parse(detail.value.draft_json)
    payload.submission_id = `ui-${crypto.randomUUID()}`
    payload.base_revision = current.value.draft_revision
    if (!nativePanel) {
    payload.content.title = String(values.title ?? '')
    payload.content.summary = String(values.summary ?? '')
    payload.content.body_markdown = String(values.body ?? '')
    }
    payload.data.task = String(values.task ?? '')
    if (payload.data.tool_version !== undefined) payload.data.tool_version = String(values.tool_version ?? '')
    if (payload.kind === 'review') payload.data.conclusions.forEach((item: { text: string }, index: number) => {
      item.text = String(values[`conclusion_${index}`] ?? '')
    })
    if (payload.kind === 'pricing') payload.data.plans.forEach((item: { amount: string | null; free_quota: { amount: string | null } }, index: number) => {
      item.amount = String(values[`amount_${index}`] ?? '').trim() || null
      item.free_quota.amount = String(values[`quota_${index}`] ?? '').trim() || null
    })
    if (payload.kind === 'leaderboard') {
      payload.data.entries.forEach((item: { rank: number; score: string | null; reason: string }, index: number) => {
        item.rank = Number(values[`rank_${index}`])
        item.score = String(values[`score_${index}`] ?? '').trim() || null
        item.reason = String(values[`reason_${index}`] ?? '')
      })
      payload.data.entries.sort((left: { rank: number }, right: { rank: number }) => left.rank - right.rank)
    }
    await packageApi.submitPackage({ body: JSON.stringify(payload) })
    const latest = await packageApi.getEntry({ id: current.value.content_id })
    installDetail(latest.data)
    showPreview.value = false
    await loadList()
    Toast.success('新草稿已保存，公开内容保持原版本。')
  } catch (error) { message.value = errorText(error); Toast.error('草稿保存失败') }
  finally { busy.value = false }
}

async function review(decision: string) {
  if (!current.value?.content_id || !current.value.draft_revision || dirty.value || busy.value || mediaPending.value) return
  busy.value = true
  message.value = ''
  try {
    const result = await packageApi.reviewEntry({ id: current.value.content_id,
      reviewCommand: { revision: current.value.draft_revision, decision, note: '后台人工审核', operation_epoch: operationEpoch.value } })
    installDetail(result.data)
    await loadList()
    Toast.success(decision === 'approve' ? '当前修订已审核通过，尚未发布。' : '当前修订已退回修改。')
  } catch (error) { message.value = errorText(error) }
  finally { busy.value = false }
}

async function publish() {
  if (!current.value?.content_id || !current.value.draft_revision || dirty.value || busy.value || mediaPending.value || !approved.value) return
  busy.value = true
  message.value = ''
  try {
    const result = await packageApi.publishEntry({ id: current.value.content_id,
      publishCommand: { revision: current.value.draft_revision, operation_epoch: operationEpoch.value } })
    installDetail(result.data)
    await loadList()
    Toast.success('审核版本已完整发布。')
  } catch (error) { message.value = errorText(error) }
  finally { busy.value = false }
}

async function changePage(delta: number) { page.value += delta; await loadList() }
function openPreview() { window.open(previewUrl.value, '_blank', 'noopener,noreferrer') }
async function changeFilter() { page.value = 1; await loadList() }
onMounted(async () => { if (nativePanel) await select(nativeContentId); else window.location.replace('/console/posts') })
</script>

<template>
  <VPageHeader title="稿件数据与审核" />
  <div class="package-workspace">
    <p class="intro">从 Halo 文章入口核对来源与结构化数据，审核当前修订，再使用原生发布按钮发布。保存新稿会保留原公开版本。</p>
    <a v-if="nativePanel" :href="`/console/posts/editor?name=${encodeURIComponent(nativePostName)}`">返回原生文章编辑器</a>
    <p v-if="message" class="error" role="alert">{{ message }}</p>
    <div class="workspace-grid" :class="{ native: nativePanel }">
      <VCard v-if="!nativePanel" title="稿件列表">
        <label class="filter-label">模块
          <select v-model="filter" @change="changeFilter"><option value="">全部</option><option v-for="(label, kind) in labels" :key="kind" :value="kind">{{ label }}</option></select>
        </label>
        <div class="item-list">
          <button v-for="item in visibleItems" :key="item.content_id" class="item" :class="{ selected: item.content_id === current?.content_id }" @click="select(item.content_id)">
            <small>{{ labels[item.kind || ''] }} · {{ item.published_revision ? '已有公开版本' : '未发布' }}</small>
            <strong>{{ item.title }}</strong>
            <span>{{ item.reviewed_revision === item.draft_revision ? '当前修订已审核' : '待审核' }}</span>
          </button>
          <p v-if="!visibleItems.length">尚无符合条件的稿件。</p>
        </div>
        <div class="pagination"><VButton :disabled="page <= 1 || busy" @click="changePage(-1)">上一页</VButton><span>{{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}</span><VButton :disabled="page * 20 >= total || busy" @click="changePage(1)">下一页</VButton></div>
      </VCard>
      <VCard :title="current ? `${labels[current.kind || '']} · 当前草稿` : '选择一份稿件'">
        <VLoading v-if="busy" />
        <template v-if="current && raw">
          <div class="actions">
            <VButton :disabled="dirty || busy || mediaPending" @click="openPreview">网页预览 ↗</VButton>
            <VButton v-if="canReview" :disabled="dirty || busy || mediaPending" @click="review('approve')">审核通过</VButton>
            <VButton v-if="canReview" :disabled="dirty || busy || mediaPending" @click="review('request_changes')">退回修改</VButton>
            <VButton v-if="canPublish && !nativePanel" type="primary" :disabled="dirty || busy || mediaPending || !approved" @click="publish">发布审核版本</VButton>
            <a v-if="currentlyPublished" :href="current.public_url" target="_blank" rel="noopener">查看公开页 ↗</a>
          </div>
          <p class="state">{{ dirty ? '有未保存修改，请先保存新草稿，再预览与审核。' : approved ? '当前修订已审核通过。' : '当前修订需要审核。' }}</p>
          <OperationsPanel :key="`${current.draft_revision}:${current.reviewed_revision || ''}`" :id="current.content_id!" :draft-json="detail?.draft_json || '{}'" :draft-revision="current.draft_revision!" :dirty="dirty || busy" @reload="select(current?.content_id)" @archived="archived = $event" @epoch="operationEpoch=$event" @published="currentlyPublished=$event" @pending="mediaPending=$event" />
          <details class="sources" open><summary>核对原始来源</summary><ul><li v-for="source in raw.sources" :key="source.source_id"><a v-if="source.url" :href="source.url" target="_blank" rel="noopener noreferrer">打开原始内容 ↗</a><span v-else>作者文件或本站材料</span> · {{ source.author || '作者待确认' }}<small>{{ source.usage_note }}</small></li></ul></details>
          <div @input.capture="formTouched = true" @change.capture="formTouched = true">
          <fieldset :disabled="archived || mediaPending || busy">
          <FormKit :key="current.draft_revision" v-model="formValues" type="form" :actions="false" @submit="saveDraft">
            <FormKit v-if="!nativePanel" name="title" type="text" label="标题" validation="required|length:1,200" />
            <FormKit v-if="!nativePanel" name="summary" type="textarea" label="摘要" validation="required|length:1,2000" />
            <FormKit name="task" type="select" label="任务分类" :options="taskOptions" validation="required" />
            <FormKit v-if="raw.data.tool_version !== undefined" name="tool_version" type="text" label="适用工具版本" validation="required" />
            <FormKit v-if="!nativePanel" name="body" type="textarea" label="正文（Markdown）" :rows="12" />
            <template v-if="raw.kind === 'review'"><FormKit v-for="(item, index) in raw.data.conclusions" :key="index" :name="`conclusion_${index}`" type="textarea" :label="`结论 ${Number(index) + 1}`" :help="item.evidence" validation="required" /></template>
            <template v-if="raw.kind === 'pricing'"><fieldset v-for="(plan, index) in raw.data.plans" :key="plan.plan_id"><legend>{{ plan.name }} · {{ plan.currency }} / {{ plan.price_unit }}</legend><FormKit :name="`amount_${index}`" type="text" label="金额" help="留空表示未知；0 表示免费。年付请填写实际全年金额。" /><FormKit :name="`quota_${index}`" type="text" :label="`免费额度（${plan.free_quota.unit}）`" help="留空表示未知，保留来源中的刷新规则。" /></fieldset></template>
            <template v-if="raw.kind === 'leaderboard'"><fieldset v-for="(item, index) in raw.data.entries" :key="item.entry_id"><legend>{{ item.tool_name }} · {{ item.model_version }}</legend><FormKit :name="`rank_${index}`" type="number" label="名次" validation="required|min:1" /><FormKit :name="`score_${index}`" type="text" :label="`成绩（${raw.data.score_unit}）`" /><FormKit :name="`reason_${index}`" type="textarea" label="推荐理由" validation="required" /></fieldset></template>
            <FormKit v-if="canEdit" type="submit" label="保存新草稿" :disabled="busy || mediaPending" />
          </FormKit>
          </fieldset>
          </div>
          <details><summary>查看完整结构化记录</summary><pre>{{ JSON.stringify(raw.data, null, 2) }}</pre></details>
        </template>
        <p v-else>从左侧选择一份已接收的稿件。</p>
      </VCard>
    </div>
  </div>
</template>

<style scoped>
.package-workspace{padding:24px}.intro,.state{color:#64748b}.workspace-grid{display:grid;grid-template-columns:minmax(220px,320px) minmax(0,1fr);gap:20px}.workspace-grid.native{grid-template-columns:1fr}.item-list{display:grid;gap:8px;margin-top:16px}.item{text-align:left;padding:14px;border:1px solid #e2e8f0;border-radius:8px;background:white;display:grid;gap:5px}.item.selected{border-color:#4169cc;background:#f0f5ff}.item small,.item span{color:#64748b}.actions,.pagination{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:12px 0}.filter-label select{margin-left:12px}.preview{width:100%;height:600px;border:1px solid #dce3ed;border-radius:8px}.error{white-space:pre-wrap;color:#b42318;background:#fff0ef;padding:12px}.sources{margin:18px 0}.sources small{display:block;color:#64748b}fieldset{border:1px solid #e2e8f0;border-radius:8px;padding:16px;margin:14px 0}pre{overflow:auto;max-height:420px;background:#f6f8fc;padding:16px}.save-button{padding:9px 18px;border:0;border-radius:6px;background:#354dbc;color:white}@media(max-width:850px){.workspace-grid{grid-template-columns:1fr}.package-workspace{padding:12px}}
</style>
