<script setup lang="ts">
import { ref } from 'vue'
import { VPageHeader, VButton, Toast } from '@halo-dev/components'
import { packageApi } from '@/api'
import { siteError } from '@/api/site-settings'
import LeaderboardFields, {type BoardEntry} from './LeaderboardFields.vue'
const title=ref('综合')
const summary=ref('')
const method=ref('')
const date=ref(new Date().toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'}))
const entries=ref<BoardEntry[]>([])
const sources=ref([{source_id:'source-'+crypto.randomUUID(),author:'',url:'',usage_note:''}])
function addSource() { sources.value.push({source_id:'source-'+crypto.randomUUID(),author:'',url:'',usage_note:''}) }
const busy=ref(false), error=ref('')
async function create() {
  busy.value=true;error.value=''
  try {
    if(!entries.value.length) throw new Error('请至少录入一个有依据的模型版本。')
    const now=new Date().toISOString(), id='overall-'+crypto.randomUUID()
    const payload={schema_version:'1.0',content_id:id,submission_id:'ui-'+crypto.randomUUID(),kind:'leaderboard',
      content:{title:title.value,summary:summary.value,body_markdown:'## 排名方法与适用范围\n\n'+method.value,tags:[],task_tags:[]},
      data:{task:'overall',benchmark:'编辑综合推荐',method_version:'1.0',as_of:date.value+'T00:00:00Z',score_unit:'custom',higher_is_better:true,allow_ties:false,ranking_mode:'editorial_order',
        entries:entries.value.map(item=>{const copy={...item,score:null};if(!copy.product_url?.trim())delete copy.product_url;return copy}).sort((a,b)=>a.rank-b.rank)},
      sources:sources.value.map(source=>({...source,url:source.url||null,author:source.author||null,original_published_at:null,retrieved_at:now,material_type:source.url?'webpage':'self_test'})),assets:[],
      provenance:{skill_name:'admin-editor',skill_version:'1.0',generated_at:now,input_sha256:null}}
    await packageApi.submitPackage({body:JSON.stringify(payload)})
    const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(id))
    const name='ap-'+Array.from(new Uint8Array(digest)).map(value=>value.toString(16).padStart(2,'0')).join('')
    Toast.success('综合榜草稿已创建，请核对并审核后发布。')
    window.location.href=`/console/aihyb-packages?content=${encodeURIComponent(id)}&post=${name}`
  } catch(e) {error.value=siteError(e)} finally {busy.value=false}
}
</script>
<template>
  <VPageHeader title="新建综合榜" />
  <main class="new-board"><p>录入具体模型版本、人工名次和依据。保存只创建草稿，审核发布后才对公众展示。</p><p v-if="error" class="error" role="alert">{{ error }}</p>
    <form @submit.prevent="create"><fieldset :disabled="busy">
      <label>榜单标题<input v-model="title" required maxlength="200" /></label><label>结论摘要<textarea v-model="summary" required maxlength="2000" /></label><label>榜单日期<input v-model="date" type="date" required /></label>
      <label>排名方法与适用范围<textarea v-model="method" rows="5" required placeholder="说明面向哪些任务、如何判断名次、依据和限制。" /></label>
      <section><h2>来源记录</h2><article v-for="source in sources" :key="source.source_id"><label>作者 / 机构<input v-model="source.author" required /></label><label>来源网址（本站整理可留空）<input v-model="source.url" type="url" /></label><label>材料与核验说明<textarea v-model="source.usage_note" required maxlength="1000" /></label></article>
        <button type="button" @click="addSource">＋ 新增来源</button></section>
      <LeaderboardFields v-model="entries" :sources="sources" overall />
      <button class="submit" type="submit">{{ busy?'保存中…':'保存综合榜草稿' }}</button>
    </fieldset></form>
  </main>
</template>
<style scoped>
.new-board{max-width:1000px;margin:24px;padding:24px;background:white;border:1px solid #dae2ef;border-radius:12px}.new-board label{display:grid;gap:6px;margin:16px 0}.new-board input,.new-board textarea{padding:9px;border:1px solid #b8c7dc;border-radius:6px;min-width:0}.new-board article{border-bottom:1px solid #dce3ef;padding-bottom:16px}.new-board fieldset{border:0;padding:0}.new-board button{padding:9px 16px;border:1px solid #cbd5e1;border-radius:6px;background:#f4f7fc;margin-top:16px}.new-board .submit{background:#2855e8;color:#fff}.new-board h2{font-size:20px;margin-top:24px}.error{white-space:pre-wrap;color:#a42620;background:#fff1f0;padding:12px}
</style>

