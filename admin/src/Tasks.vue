<script setup lang="ts">
import {onMounted, reactive, ref, watch} from 'vue';
import {api, display, type Actor, type Row} from './api';
import DataTable from './DataTable.vue';
const props=defineProps<{me:Actor; management?:boolean; taskId?:number|null}>();
const states:Record<string,string>={TODO:'待处理',IN_PROGRESS:'进行中',COMPLETED:'已完成',CANCELLED:'已取消'};
const rows=ref<Row[]>([]),people=ref<Row[]>([]),detail=ref<Row|null>(null),editing=ref(false),busy=ref(false),error=ref(''),message=ref(''),offset=ref(0),content=ref('');
const filters=reactive({scope:props.management?'':'assigned',status:'',dueFrom:'',dueTo:'',creatorId:'',assigneeId:''});
const form=reactive({title:'',description:'',dueAt:'',assigneeIds:[] as number[]});
async function run(fn:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';message.value='';try{await fn()}catch(e){error.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}}
async function load(){const q=new URLSearchParams({offset:String(offset.value)});Object.entries(filters).forEach(([k,v])=>{if(v)q.set(k,v)});rows.value=await api('/tasks?'+q)}
async function open(id:number){detail.value=await api('/tasks/'+id);editing.value=false;content.value=''}
function edit(create=false){if(create)detail.value=null;const d=detail.value;Object.assign(form,{title:d?.title||'',description:d?.description||'',dueAt:d?.due_at||'',assigneeIds:d?d.assignees.map((p:Row)=>p.user_id):[]});editing.value=true}
async function save(){const r=await api(detail.value?'/tasks/'+detail.value.id:'/tasks',detail.value?'PUT':'POST',form);await open(r.id);await load();message.value='任务已保存'}
async function status(next:string){await api('/tasks/'+detail.value!.id+'/status','POST',{status:next,content:content.value});await open(detail.value!.id);await load();message.value='任务状态已更新'}
async function follow(){await api('/tasks/'+detail.value!.id+'/followups','POST',{content:content.value});await open(detail.value!.id);message.value='跟进已追加'}
onMounted(()=>run(async()=>{people.value=[{...props.me,employee_no:'本人'},...await api('/colleagues')];await load();if(props.taskId)await open(props.taskId)}));
watch(()=>props.taskId,id=>{if(id)run(()=>open(id))});
</script>
<template>
<section class="card">
  <p v-if="error" class="error" role="alert">{{error}}</p><p v-if="message" class="success" role="status">{{message}}</p>
  <div class="section-toolbar"><h2>待办任务</h2><button :disabled="busy" @click="edit(true)">创建任务</button></div>
  <p>指派与跟进，无需审批。截止日期按北京时间，仅作提醒；每页最多200条。</p>
  <form class="filters" @submit.prevent="run(async()=>{offset=0;await load()})">
    <label>任务范围<select v-model="filters.scope"><option value="">{{management?'全部任务':'我有权查看的任务'}}</option><option value="assigned">我负责的</option><option value="initiated">我发起的（未取消）</option></select></label>
    <label>任务状态<select v-model="filters.status"><option value="">全部状态</option><option v-for="(v,k) in states" :key="k" :value="k">{{v}}</option></select></label>
    <label>截止起日<input v-model="filters.dueFrom" type="date" /></label><label>截止止日<input v-model="filters.dueTo" type="date" /></label>
    <label>发起人<select v-model="filters.creatorId" aria-label="发起人筛选"><option value="">全部</option><option v-for="p in people" :key="p.id" :value="String(p.id)">{{p.name}}</option></select></label>
    <label>负责人<select v-model="filters.assigneeId" aria-label="负责人筛选"><option value="">全部</option><option v-for="p in people" :key="p.id" :value="String(p.id)">{{p.name}}</option></select></label>
    <button :disabled="busy">查询任务</button>
  </form>
  <DataTable :rows="rows.map(r=>({...r,status_text:states[r.status],due_text:display(r.due_at)+(r.overdue?'（已过期）':'')}))" :columns="[{key:'title',label:'标题'},{key:'creator_name',label:'发起人'},{key:'status_text',label:'状态'},{key:'due_text',label:'截止时间'}]"><template #actions="{row}"><button :disabled="busy" @click="run(()=>open(row.id))">查看任务</button></template></DataTable>
  <div class="pagination"><span>第 {{offset/200+1}} 页</span><button :disabled="busy||offset===0" @click="run(async()=>{offset-=200;await load()})">上一页</button><button :disabled="busy||rows.length<200" @click="run(async()=>{offset+=200;await load()})">下一页</button></div>
</section>
<div v-if="editing||detail" class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" :aria-label="editing?'编辑任务':'任务详情'">
  <div class="modal-header"><h2>{{editing?'编辑任务':'任务详情'}}</h2><button @click="editing=false;detail=null">关闭任务</button></div>
  <p v-if="error" class="error" role="alert">{{error}}</p>
  <form v-if="editing" @submit.prevent="run(save)">
    <label>任务标题<input v-model="form.title" required maxlength="200" /></label><label>任务说明<textarea v-model="form.description" maxlength="5000" rows="4" /></label>
    <label>截止日期时间（北京时间，可选）<input v-model="form.dueAt" type="datetime-local" step="1" :disabled="detail?.status==='COMPLETED'" /></label>
    <fieldset class="task-assignees" :disabled="detail?.status==='COMPLETED'"><legend>负责人（至少1人，最多200人）</legend><label class="check" v-for="p in people" :key="p.id"><input v-model="form.assigneeIds" type="checkbox" :value="p.id" />{{p.name}} · {{p.employee_no}}</label><label class="check" v-for="p in (detail?.assignees||[]).filter((p:Row)=>!people.some(u=>u.id===p.user_id))" :key="p.user_id"><input v-model="form.assigneeIds" type="checkbox" :value="p.user_id" />{{p.name}}（已停用，保存前请移出）</label></fieldset>
    <button class="primary" :disabled="busy">保存任务</button><button v-if="detail" type="button" @click="editing=false">返回任务详情</button>
  </form>
  <div v-else-if="detail">
    <h3>{{detail.title}}</h3><p>状态：{{states[detail.status]}} · 发起人：{{detail.creator_name}}</p><p>截止：{{display(detail.due_at)}} <strong v-if="detail.overdue" class="error">已过期</strong></p>
    <p style="white-space:pre-wrap">{{detail.description||'未填写说明'}}</p><p>负责人：{{detail.assignees.map((p:Row)=>p.name).join('、')}}</p>
    <h3>跟进记录</h3><p v-if="!detail.followups.length">暂无跟进</p><article v-for="f in detail.followups" :key="f.id"><p>{{f.user_name}} · {{display(f.created_at)}}</p><p style="white-space:pre-wrap">{{f.content}}</p></article>
    <template v-if="detail.status!=='CANCELLED'"><label>跟进说明（完成时建议填写）<textarea v-model="content" maxlength="5000" /></label><div class="actions"><button :disabled="busy||!content.trim()" @click="run(follow)">追加跟进</button><button v-if="['TODO','IN_PROGRESS'].includes(detail.status)" :disabled="busy" @click="run(()=>status(detail!.status==='TODO'?'IN_PROGRESS':'TODO'))">{{detail.status==='TODO'?'开始处理':'改为待处理'}}</button><button v-if="['TODO','IN_PROGRESS'].includes(detail.status)" :disabled="busy" @click="run(()=>status('COMPLETED'))">标记已完成</button></div></template>
    <div class="actions"><button v-if="detail.canEdit&&detail.status!=='CANCELLED'" :disabled="busy" @click="edit()">修改任务</button><button v-if="detail.canEdit&&detail.status!=='CANCELLED'" :disabled="busy" @click="run(()=>status('CANCELLED'))">取消任务</button><button v-if="detail.canRestore&&['COMPLETED','CANCELLED'].includes(detail.status)" :disabled="busy" @click="run(()=>status(detail!.status==='CANCELLED'?'TODO':'IN_PROGRESS'))">{{detail.status==='CANCELLED'?'恢复为待处理':'重开为进行中'}}</button></div>
  </div>
</section></div>
</template>

<style scoped>
.task-assignees { max-height: 260px; overflow-y: auto; }
.task-assignees .check { display: flex; overflow-wrap: anywhere; }
</style>
