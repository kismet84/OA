<script setup lang="ts">
import ApprovalAdmin from './ApprovalAdmin.vue';
import {onMounted,ref,reactive,watch} from 'vue';
import {api,download,binary,type Actor,type Row} from './api';
import DataTable from './DataTable.vue';
const props=defineProps<{me:Actor;contractId?:number|null}>();
const states:Row={DRAFT:'草稿',PENDING:'审批中',REJECTED:'已驳回',WITHDRAWN:'已撤回',TO_ARCHIVE:'待归档',ACTIVE:'履行中',EXPIRED:'已到期',TERMINATED:'已终止'};
const types:Row={PURCHASE:'采购',SALES:'销售',SERVICE:'服务',LEASE:'租赁',LABOR:'劳务',OTHER:'其他'};
const rows=ref<Row[]>([]),detail=ref<Row|null>(null),editing=ref(false),editId=ref<number|null>(null),busy=ref(false),error=ref(''),message=ref(''),expiring=ref(0),offset=ref(0),sealed=ref<Row[]>([]),reason=ref('');
const fields=[['code','合同编号（留空自动生成）'],['name','合同名称'],['counterparty','对方单位'],['contact','对方联系人'],['phone','联系电话'],['amount','金额（元，无固定金额可留空）'],['signedDate','签订日期'],['effectiveDate','生效日期'],['expiryDate','到期日期（留空为长期）'],['performance','付款或履约说明'],['note','备注']];
const form=reactive<Row>({}),files=ref<Row[]>([]);
const filters=reactive<Row>({scope:'visible',status:'',type:'',departmentId:'',counterparty:'',expiryFrom:'',expiryTo:''});
const management=props.me.roles.split(',').some(r=>['ADMIN','FINANCE'].includes(r));
const editable=(d:Row)=>d.isOwner&&['DRAFT','REJECTED','WITHDRAWN'].includes(d.workflow_status);
function params(){return new URLSearchParams(Object.fromEntries(Object.entries(filters).filter(([,v])=>v!=='')))}
async function run(fn:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';message.value='';try{await fn()}catch(e){error.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}}
async function load(){rows.value=await api('/contracts?'+params()+'&offset='+offset.value);expiring.value=(await api('/contracts/summary')).expiring30}
async function open(id:number){detail.value=await api('/contracts/'+id);editing.value=false;sealed.value=[];reason.value=''}
function edit(d?:Row){editId.value=d?.id||null;Object.keys(form).forEach(k=>delete form[k]);for(const [k] of fields){const snake=k!.replace(/[A-Z]/g,c=>'_'+c.toLowerCase());form[k!]=d?.[snake]??''}form.type=d?.type||'SERVICE';files.value=d?.files.map((f:Row)=>({id:f.attachment_id,name:f.original_name}))||[];detail.value=null;editing.value=true}
async function save(submit=false){const data={...form,amount:form.amount===''?null:Number(form.amount),expiryDate:form.expiryDate||null,attachmentIds:files.value.map(f=>f.id)};const r=await api('/contracts'+(editId.value?'/'+editId.value:''),editId.value?'PUT':'POST',data);editId.value=r.id;if(submit)await api('/contracts/'+r.id+'/submit','POST');editing.value=false;await load();await open(r.id);message.value=submit?'合同已提交':'草稿已保存'}
async function act(action:string,data?:Row){const id=detail.value!.id;await api('/contracts/'+id+'/'+action,'POST',data);await load();await open(id);message.value='操作已完成'}
async function upload(e:Event,target:Row[]){for(const file of Array.from((e.target as HTMLInputElement).files||[])){const data=new FormData();data.append('file',file);target.push(await api('/attachments','POST',data))}(e.target as HTMLInputElement).value=''}
const preview=ref(''),previewType=ref('');function closePreview(){if(preview.value)URL.revokeObjectURL(preview.value);preview.value=''}
async function view(f:Row){closePreview();preview.value=URL.createObjectURL(await binary('/contracts/'+detail.value!.id+'/files/'+f.attachment_id+'?preview=true'));previewType.value=f.media_type}
onMounted(()=>run(async()=>{await load();if(props.contractId)await open(props.contractId)}));watch(()=>props.contractId,id=>{if(id)run(()=>open(id))});
</script>
<template>
<section class="card"><h2>合同管理</h2><p>简单合同台账与审批 · 北京时间 · 30 天内到期：{{expiring}} 份</p>
<p v-if="error" class="error" role="alert">{{error}}</p><p v-if="message" class="success" role="status">{{message}}</p>
<div class="actions"><button :disabled="busy" @click="edit()">登记合同</button><button :disabled="busy" @click="run(load)">刷新合同</button><button v-if="management" :disabled="busy" @click="run(()=>download('/contracts/export?'+params(),'合同台账.csv'))">导出合同 CSV</button></div>
<form class="filters" @submit.prevent="run(async()=>{offset=0;await load()})">
<label>合同范围<select v-model="filters.scope"><option value="visible">可见合同</option><option value="mine">我的合同</option><option value="pending">合同待办</option></select></label>
<label>合同状态<select v-model="filters.status"><option value="">全部状态</option><option v-for="(v,k) in states" :value="k" :key="k">{{v}}</option></select></label>
<label>合同类型筛选<select v-model="filters.type"><option value="">全部类型</option><option v-for="(v,k) in types" :value="k" :key="k">{{v}}</option></select></label>
<label>所属部门ID<input v-model="filters.departmentId" type="number" min="1" /></label><label>对方单位关键字<input v-model="filters.counterparty" /></label><label>到期区间起<input v-model="filters.expiryFrom" type="date" /></label><label>到期区间止<input v-model="filters.expiryTo" type="date" /></label><button :disabled="busy">查询合同</button></form>
<DataTable :rows="rows.map(r=>({...r,status_text:states[r.status],type_text:types[r.type]}))" :columns="[{key:'code',label:'合同编号'},{key:'name',label:'合同名称'},{key:'type_text',label:'类型'},{key:'counterparty',label:'对方单位'},{key:'user_name',label:'经办人'},{key:'department_name',label:'部门'},{key:'amount',label:'金额'},{key:'expiry_date',label:'到期日期'},{key:'status_text',label:'状态'}]"><template #actions="{row}"><button @click="run(()=>open(row.id))">查看合同</button></template></DataTable>
<div class="pagination"><button :disabled="busy||!offset" @click="run(async()=>{offset-=200;await load()})">上一页</button><span>第 {{offset/200+1}} 页</span><button :disabled="busy||rows.length<200" @click="run(async()=>{offset+=200;await load()})">下一页</button></div>
</section>
<div v-if="editing||detail" class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-label="合同详情与登记"><div class="modal-header"><h2>{{editing?'合同登记':'合同详情'}}</h2><button @click="editing=false;detail=null;closePreview()">关闭合同窗口</button></div><p v-if="error" class="error" role="alert">{{error}}</p>
<form v-if="editing" @submit.prevent="run(()=>save(false))"><label v-for="[k,label] in fields" :key="k">{{label}}<textarea v-if="['performance','note'].includes(k!)" v-model="form[k!]" maxlength="5000" /><input v-else v-model="form[k!]" :type="k!.endsWith('Date')?'date':k==='amount'?'number':'text'" :min="k==='amount'?0:undefined" :step="k==='amount'?'0.01':undefined" :required="['name','counterparty','signedDate','effectiveDate'].includes(k!)" :maxlength="k==='code'?100:k==='contact'?100:k==='phone'?50:200" /></label>
<label>合同类型<select v-model="form.type"><option v-for="(v,k) in types" :key="k" :value="k">{{v}}</option></select></label>
<p>经办人为本人，所属部门按经办人登记。提交后锁定字段；驳回或撤回后可修改再提交。</p>
<label>合同附件（多份 PDF/PNG/JPEG，每份≤10MB）<input type="file" multiple accept=".pdf,.png,.jpg,.jpeg" @change="run(()=>upload($event,files))" /></label><p v-for="(f,i) in files" :key="f.id">{{f.name}} <button type="button" @click="files.splice(i,1)">移除附件</button></p>
<button :disabled="busy">保存草稿</button><button type="button" :disabled="busy" @click="run(()=>save(true))">保存并提交合同</button></form>
<template v-else-if="detail"><h3>{{detail.code}} · {{detail.name}}</h3><p>{{states[detail.status]}} · {{types[detail.type]}} · 经办人：{{detail.user_name}} · 部门：{{detail.department_name}}</p><p v-if="detail.status==='EXPIRED'">流程状态：{{states[detail.workflow_status]}}</p><p>对方单位：{{detail.counterparty}} · 联系人：{{detail.contact||'无'}} · 电话：{{detail.phone||'无'}}</p><p>金额：{{detail.amount??'无固定金额'}} 元</p><p>签订：{{detail.signed_date}} · 生效：{{detail.effective_date}} · 到期：{{detail.expiry_date||'长期'}}</p><p>付款或履约说明：{{detail.performance||'无'}}</p><p>备注：{{detail.note||'无'}}</p><p v-if="detail.termination_reason">终止原因：{{detail.termination_reason}}</p>
<ul><li v-for="f in detail.files" :key="f.attachment_id">{{f.sealed?'盖章版':'草稿/扫描件'}} · {{f.original_name}} <button @click="run(()=>view(f))">预览合同附件</button><button @click="run(()=>download('/contracts/'+detail!.id+'/files/'+f.attachment_id,f.original_name))">下载合同附件</button></li></ul>
<template v-if="preview"><button @click="closePreview">关闭预览</button><iframe v-if="previewType==='application/pdf'" :src="preview" title="合同附件预览" style="width:100%;height:480px" /><img v-else :src="preview" alt="合同附件预览" style="max-width:100%" /></template>
<p v-for="h in detail.reassignments" :key="'reassign'+h.id">管理员 {{h.actor_name}} 改派：{{h.previous_approver_name||'财务或管理员'}} → {{h.approver_name}} · {{h.note}} · {{h.created_at}}</p><ApprovalAdmin v-if="me?.roles.split(',').includes('ADMIN') && detail.workflow_status==='PENDING'" :path="'/contracts/'+detail.id" :owner-id="detail.user_id"  @done="run(async()=>{await open(detail!.id);await load()})" /><h3>审批记录</h3><p v-for="s in detail.steps" :key="s.revision+':'+s.step">第 {{s.revision}} 次提交 · 第 {{s.step}} 级 · {{s.approver_name||'财务或管理员'}} · {{s.status==='APPROVED'?'通过':s.status==='REJECTED'?'驳回':'待处理'}} · {{s.actor_name||''}} {{s.note||''}}</p>
<div v-if="editable(detail)" class="actions"><button @click="edit(detail!)">修改合同</button><button :disabled="busy" @click="run(()=>act('submit'))">提交合同审批</button><button v-if="detail.workflow_status==='DRAFT'" :disabled="busy" @click="run(async()=>{await api('/contracts/'+detail!.id,'DELETE');detail=null;await load()})">删除合同草稿</button></div>
<label v-if="detail.canApprove||detail.isOwner||me.roles.includes('ADMIN')">处理说明（驳回或终止必填）<textarea v-model="reason" maxlength="1000" /></label>
<div v-if="detail.canApprove" class="actions"><button :disabled="busy" @click="run(()=>act('approve',{approved:true,note:reason}))">同意合同</button><button :disabled="busy" @click="run(()=>act('approve',{approved:false,note:reason}))">驳回合同</button></div>
<button v-if="detail.isOwner&&detail.workflow_status==='PENDING'" :disabled="busy" @click="run(()=>act('withdraw'))">撤回合同</button>
<template v-if="detail.canArchive&&detail.workflow_status==='TO_ARCHIVE'"><label>上传盖章版（多份，每份≤10MB）<input type="file" multiple accept=".pdf,.png,.jpg,.jpeg" @change="run(()=>upload($event,sealed))" /></label><p v-for="(f,i) in sealed" :key="f.id">{{f.name}} <button @click="sealed.splice(i,1)">移除盖章版</button></p><button :disabled="busy||!sealed.length" @click="run(()=>act('archive',{attachmentIds:sealed.map(f=>f.id)}))">归档合同</button></template>
<button v-if="(detail.isOwner||me.roles.includes('ADMIN'))&&!['DRAFT','TERMINATED'].includes(detail.workflow_status)" :disabled="busy" @click="run(()=>act('terminate',{reason}))">终止合同</button>
</template></section></div>
</template>
