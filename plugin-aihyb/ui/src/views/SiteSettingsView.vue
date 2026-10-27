<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VPageHeader, VButton, Toast } from '@halo-dev/components'
import { siteApi, sceneLabels, siteError, type SiteSettings, type QrSlot } from '@/api/site-settings'
const state = ref<SiteSettings>()
const busy = ref(false)
const error = ref('')
async function load() { busy.value=true; try { state.value=(await siteApi.get()).data; error.value='' } catch(e) { error.value=siteError(e) } finally {busy.value=false} }
async function save() {
  if(!state.value || busy.value) return
  busy.value=true; error.value=''
  try { state.value=(await siteApi.save(state.value)).data; Toast.success('站点配置已保存，全站展示同步更新。') } catch(e) { error.value=siteError(e) } finally { busy.value=false }
}
function move(index: number, offset: number) {
  const list=state.value?.tags; if(!list || index+offset<0 || index+offset>=list.length) return
  const tag=list.splice(index,1)[0]; if(tag) list.splice(index+offset,0,tag)
}
async function image(event: Event, qr: QrSlot) {
  const input=event.target as HTMLInputElement;const file=input.files?.[0]; if(!file) return
  busy.value=true; error.value=''
  try {
    if(!['image/png','image/jpeg','image/webp'].includes(file.type)||file.size>5*1024*1024) throw new Error('请选择 5 MB 以内的 PNG、JPEG 或 WebP 二维码。')
    const bitmap=await createImageBitmap(file)
    if(bitmap.width<64||bitmap.height<64) {bitmap.close();throw new Error('二维码尺寸至少为 64×64 像素。')}
    const scale=Math.min(1,512/Math.max(bitmap.width,bitmap.height)); const canvas=document.createElement('canvas')
    canvas.width=Math.round(bitmap.width*scale);canvas.height=Math.round(bitmap.height*scale)
    const context=canvas.getContext('2d'); if(!context) {bitmap.close();throw new Error('浏览器无法处理图片，请换一个浏览器重试。')}
    context.fillStyle='#fff';context.fillRect(0,0,canvas.width,canvas.height);context.drawImage(bitmap,0,0,canvas.width,canvas.height);bitmap.close()
    const encoded=canvas.toDataURL('image/png'); if(encoded.length>65558) throw new Error('图片内容过大，请使用清晰的纯二维码图片，去除海报背景后再上传。')
    qr.image=encoded
  } catch(e) {error.value=siteError(e)} finally {input.value='';busy.value=false}
}
function addTag() { state.value?.tags.push({id:'tag-'+crypto.randomUUID(),label:'',enabled:true,scenes:[]}) }
const qrLabels: Record<string,string>={group:'微信群',account:'公众号',support:'客服'}
onMounted(load)
</script>
<template>
  <VPageHeader title="AI好用榜 · 站点配置" />
  <main class="site-config">
    <p>统一维护四模块任务标签、外链关系和页脚二维码。修改后点击“保存配置”生效。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form v-if="state" @submit.prevent="save">
      <fieldset :disabled="busy">
        <section><h2>任务标签与场景</h2><p>四模块共用。一个标签可属于多个场景；停用会隐藏筛选入口，保留文章关联。“综合”由榜单单独人工维护。</p>
          <div v-for="(tag,index) in state.tags" :key="tag.id" class="tag-row">
            <label>名称<input v-model="tag.label" required maxlength="40" /></label>
            <label class="inline"><input type="checkbox" v-model="tag.enabled" />启用</label>
            <div class="scene-choices"><label v-for="(label,id) in sceneLabels" :key="id"><input type="checkbox" :value="id" v-model="tag.scenes" />{{ label }}</label></div>
            <button type="button" :disabled="index===0" @click="move(index,-1)" :aria-label="`${tag.label}上移`">上移</button><button type="button" :disabled="index===state.tags.length-1" @click="move(index,1)" :aria-label="`${tag.label}下移`">下移</button>
          </div>
          <button type="button" @click="addTag">＋ 新增标签</button>
        </section>
        <section><h2>页脚二维码</h2><p>没有上传的项目不显示。上传后预览图片，再保存配置；可分别替换或清空。</p>
          <div class="qr-grid"><article v-for="qr in state.qrs" :key="qr.id"><h3>{{ qrLabels[qr.id] }}</h3>
            <img v-if="qr.image" :src="qr.image" :alt="qr.label+'预览'" /><p v-else class="empty">尚未上传，前台隐藏</p>
            <label>上传或更换图片<input type="file" accept="image/png,image/jpeg,image/webp" @change="image($event,qr)" /></label>
            <label>显示名称<input v-model="qr.label" maxlength="40" required /></label>
            <label>说明或可复制的联系名称<input v-model="qr.description" maxlength="160" /></label>
            <label>到期日期（可不填）<input type="date" v-model="qr.expiresOn" /></label>
            <button v-if="qr.image" type="button" @click="qr.image=''">清空此二维码</button>
          </article></div>
          <p>过期图片自动隐藏。微信群码过期后，如有有效客服二维码则提示联系；否则提示等待更新。请同时维护客服二维码。</p>
        </section>
        <section><h2>外链关系</h2><p>稿件中的来源引用默认作为普通链接，其他外链默认不背书。这里可为完整地址指定关系，覆盖全站对应链接；站内链接不受影响。</p>
          <div v-for="(rule,index) in state.links" :key="index" class="link-row"><label>完整网址<input v-model="rule.url" type="url" required placeholder="https://…" /></label>
            <label>关系<select v-model="rule.relation"><option value="reference">可信引用 / 官方入口</option><option value="sponsored">广告 / 赞助 / 返佣</option><option value="ugc">用户提交</option><option value="nofollow">无法背书</option></select></label>
            <button type="button" @click="state.links.splice(index,1)">移除此规则</button></div>
          <button type="button" @click="state.links.push({url:'',relation:'reference'})">＋ 新增外链规则</button>
        </section>
      </fieldset>
      <div class="save-bar"><VButton type="primary" :disabled="busy" @click="save">{{ busy?'处理中…':'保存配置' }}</VButton><span>文章的任务标签仍需在稿件中保存、审核并发布。</span></div>
    </form>
  </main>
</template>
<style scoped>
.site-config{padding:24px;max-width:1320px;color:#26364b}.site-config p{color:#526079}.site-config section{background:#fff;border:1px solid #dce3ef;border-radius:12px;padding:24px;margin:20px 0}.site-config h2{font-size:20px;font-weight:650;margin-bottom:8px}.site-config h3{font-weight:650}.site-config fieldset{border:0;padding:0;min-width:0}.site-config label{display:grid;gap:6px;font-size:14px}.site-config input:not([type=checkbox]),.site-config select{padding:8px;border:1px solid #b8c7dc;border-radius:6px;max-width:100%;min-width:0}.site-config button{padding:8px 12px;border:1px solid #cbd5e1;border-radius:6px;background:#f4f7fc}.site-config button:disabled{opacity:.45}.tag-row{display:flex;align-items:center;gap:12px;flex-wrap:wrap;border-bottom:1px solid #e2e8f0;padding:14px 0}.scene-choices{display:flex;gap:10px;flex-wrap:wrap;flex:1;min-width:260px}.scene-choices label,.inline{display:flex!important;align-items:center;gap:5px}.qr-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px;margin:20px 0}.qr-grid article{display:grid;gap:14px;align-content:start}.qr-grid img,.empty{width:160px;height:160px;object-fit:contain;background:#f6f8fc;padding:8px}.empty{display:grid;place-items:center}.link-row{display:flex;gap:14px;align-items:end;margin:16px 0}.link-row label:first-child{flex:1}.save-bar{position:sticky;bottom:0;background:#fff;padding:16px;border-top:1px solid #dce3ef;display:flex;gap:18px;align-items:center;flex-wrap:wrap}.error{color:#a42620!important;background:#fff1f0;padding:14px;white-space:pre-wrap}@media(max-width:760px){.qr-grid{grid-template-columns:1fr}.link-row{flex-direction:column;align-items:stretch}.site-config{padding:12px}.site-config section{padding:16px}}
</style>

