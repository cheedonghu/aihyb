<script setup lang="ts">
export type BoardEntry = {entry_id:string;tool_id:string;tool_name:string;model_version:string;rank:number;score:string|null;reason:string;source_id:string;product_url?:string}
const entries=defineModel<BoardEntry[]>({required:true})
defineProps<{sources:{source_id:string;author?:string;url?:string|null}[]; overall?:boolean}>()
function add() {const id='model-'+crypto.randomUUID();entries.value.push({entry_id:id,tool_id:id,tool_name:'',model_version:'',rank:entries.value.length+1,score:null,reason:'',source_id:''})}
</script>
<template>
  <div class="board-fields">
    <p v-if="overall">综合榜按具体模型版本人工排序。原始成绩可留空，不计算综合总分。</p>
    <fieldset v-for="(item,index) in entries" :key="item.entry_id">
      <legend>第 {{ index+1 }} 项 · {{ item.tool_name || '新模型' }}</legend>
      <div class="board-grid"><label>名次<input v-model.number="item.rank" type="number" min="1" required /></label><label>模型名称<input v-model="item.tool_name" maxlength="200" required /></label><label>具体版本<input v-model="item.model_version" maxlength="120" required /></label></div>
      <label v-if="!overall">原始成绩（可留空）<input v-model="item.score" /></label>
      <label>推荐理由<textarea v-model="item.reason" rows="3" required maxlength="1000" /></label>
      <label>来源<select v-model="item.source_id" required><option value="">请选择来源</option><option v-for="source in sources" :value="source.source_id" :key="source.source_id">{{ source.author || source.url || source.source_id }}</option></select></label>
      <label>产品使用入口（可选）<input v-model="item.product_url" type="url" placeholder="https://…" /></label>
      <button type="button" @click="entries.splice(index,1)">移除此项</button>
    </fieldset>
    <button type="button" @click="add">＋ 新增模型版本</button>
  </div>
</template>
<style scoped>
.board-fields fieldset{border:1px solid #d6e0ee;border-radius:10px;padding:18px;margin:18px 0}.board-fields label{display:grid;gap:6px;margin:10px 0;font-size:14px}.board-fields input,.board-fields textarea,.board-fields select{border:1px solid #b8c7dc;border-radius:6px;padding:9px;min-width:0;max-width:100%}.board-grid{display:grid;grid-template-columns:90px 1fr 1fr;gap:16px}.board-fields button{border:1px solid #ccd6e5;border-radius:6px;padding:8px 12px;background:#f5f8fd}.board-fields p{color:#526079}@media(max-width:650px){.board-grid{grid-template-columns:1fr}}
</style>

