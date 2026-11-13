<script setup lang="ts">
import ApprovalAdmin from './ApprovalAdmin.vue';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { api, download, display, type Actor, type Row } from './api';
import DataTable from './DataTable.vue';
const props=defineProps<{me:Actor;management?:boolean;requestId?:number|null}>();
const states:Row={AVAILABLE:'在库',ASSIGNED:'已领用',REPAIR:'维修中',SCRAPPED:'已报废',PENDING:'待审批',READY:'待发放',ISSUED:'已发放',IN_USE:'使用中',RETURNED:'已归还',REJECTED:'已驳回',WITHDRAWN:'已撤回'};
const tab=ref(props.management?'ledger':'apply'), rows=ref<Row[]>([]), categories=ref<Row[]>([]), catalog=ref<Row[]>([]), ledger=ref<Row[]>([]), detail=ref<Row|null>(null), busy=ref(false), error=ref(''), message=ref(''), offset=ref(0);
const editor=ref(''), editId=ref<number|null>(null), form=reactive<Row>({}), note=ref(''), repair=ref(false), selected=ref<number[]>([]), choice=ref('');
const filters=reactive({userId:'',departmentId:'',assetId:''});
const tabs=computed(()=>[...(props.management?[['ledger','资产台账'],['categories','资产分类'],['all','领用记录']]:[]),['apply','发起领用'],['mine','我的申请'],['holdings','我的资产'],['pending','资产待办']]);
const chosen=computed(()=>catalog.value.find(c=>c.kind+':'+c.id===choice.value));
const available=computed(()=>ledger.value.filter(a=>a.category_id===detail.value?.category_id && a.status==='AVAILABLE' && a.enabled));
const cols=(pairs:string[])=>pairs.map(p=>{const [key,label]=p.split(':');return {key:key!,label:label!}});
async function run(fn:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';message.value='';try{await fn()}catch(e){error.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}}
async function load(){
 catalog.value=await api('/assets/catalog');
 if(props.management)categories.value=await api('/assets/categories');
 if(tab.value==='apply'){Object.assign(form,{quantity:1,purpose:'',expectedDate:'',needsReturn:true,returnDate:''});return}
 const base=tab.value==='ledger'?'/assets':tab.value==='categories'?'/assets/categories':tab.value==='holdings'?'/assets/mine':'/assets/requests';
 rows.value=await api(base+'?'+new URLSearchParams({offset:String(offset.value),scope:tab.value==='all'?'all':tab.value==='pending'?'pending':'mine',...(tab.value==='all'?Object.fromEntries(Object.entries(filters).filter(([,v])=>v)): {})}));
}
async function open(id:number){detail.value=await api('/assets/requests/'+id);editor.value='';note.value='';selected.value=[];repair.value=false;if(detail.value!.canManage && detail.value!.status==='READY')ledger.value=await api('/assets?status=AVAILABLE&categoryId='+detail.value!.category_id);}
async function act(action:string,data?:Row){await api('/assets/requests/'+detail.value!.id+'/'+action,'POST',data);if(action==='approve'){detail.value=null}else{await open(detail.value!.id)}await load();message.value='操作已完成'}
async function apply(){if(!chosen.value)throw Error('请选择资产');const c=chosen.value;const r=await api('/assets/requests','POST',{categoryId:c.kind==='ITEM'?c.id:null,consumableId:c.kind==='CONSUMABLE'?c.id:null,quantity:Number(form.quantity),purpose:form.purpose,expectedDate:form.expectedDate,needsReturn:c.kind==='ITEM'&&form.needsReturn,returnDate:form.returnDate||null});tab.value='mine';await load();await open(r.id);message.value='领用申请已提交'}
function edit(kind:string,row?:Row){editor.value=kind;editId.value=row?.id||null;detail.value=null;Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,kind==='category'?{name:row?.name||'',kind:row?.kind||'ITEM',enabled:row?.enabled??true}:kind==='asset'?{categoryId:row?.category_id||categories.value.find(c=>c.enabled)?.id,code:row?.code||'',name:row?.name||'',brand:row?.brand||'',serialNumber:row?.serial_number||'',purchaseDate:row?.purchase_date||'',location:row?.location||'',note:row?.note||'',attachmentId:row?.attachment_id||null,unit:row?.unit||'个',enabled:row?.enabled??true}:{reason:'',delta:1,status:'AVAILABLE',kind:row?.kind});}
async function save(){let path=editor.value==='category'?'/assets/categories':'/assets';let data={...form};if(editor.value==='adjust'){path+='/'+editId.value+'/adjust';data={reason:form.reason,...(form.kind==='ITEM'?{status:form.status}:{delta:Number(form.delta)})}}else{if(editId.value)path+='/'+editId.value;if(editor.value==='asset')data.purchaseDate=form.purchaseDate||null}await api(path,editId.value&&editor.value!=='adjust'?'PUT':'POST',data);editor.value='';await load();message.value='已保存'}
async function upload(e:Event){const f=(e.target as HTMLInputElement).files?.[0];if(f){const data=new FormData();data.append('file',f);form.attachmentId=(await api('/attachments','POST',data)).id}}
onMounted(()=>run(async()=>{await load();if(props.requestId)await open(props.requestId)}));
watch(()=>props.requestId,id=>{if(id)run(()=>open(id))});
</script>
<template>
<section class="card">
 <h2>资产领用</h2><p>简单资产台账与领用流程 · 时间按北京时间 · 每页最多200条</p>
 <p v-if="error" role="alert" class="error">{{error}}</p><p v-if="message" role="status" class="success">{{message}}</p>
 <div class="actions"><button v-for="[key,label] in tabs" :key="key" :disabled="busy" @click="run(async()=>{tab=key!;offset=0;await load()})">{{label}}</button><button :disabled="busy" @click="run(load)">刷新资产</button></div>
 <form v-if="tab==='apply'" @submit.prevent="run(apply)">
 <label>可申请资产<select v-model="choice" required @change="form.needsReturn=chosen?.kind==='ITEM'"><option value="">请选择</option><option v-for="c in catalog" :key="c.kind+':'+c.id" :value="c.kind+':'+c.id">{{c.name}} · {{c.kind==='ITEM'?'按件':'消耗品'}} · 可用 {{c.available}} {{c.unit||'件'}}</option></select></label>
 <label>数量<input v-model.number="form.quantity" type="number" min="1" max="100000" required /></label><label>用途<textarea v-model="form.purpose" required maxlength="2000" /></label><label>期望领用日期<input v-model="form.expectedDate" type="date" required /></label>
 <template v-if="chosen?.kind==='ITEM'"><label><input v-model="form.needsReturn" type="checkbox" />需要归还</label><label v-if="form.needsReturn">预计归还日期（可选）<input v-model="form.returnDate" type="date" :min="form.expectedDate" /></label></template>
 <p>先由直属上级审批，无直属上级直接待发放。消耗品领用后不需要归还。</p><button :disabled="busy" class="primary">提交领用申请</button></form>
 <template v-else>
 <button v-if="tab==='ledger'||tab==='categories'" :disabled="busy" @click="edit(tab==='ledger'?'asset':'category')">{{tab==='ledger'?'新增资产':'新增分类'}}</button>
 <form v-if="tab==='all'" class="filters" @submit.prevent="run(async()=>{offset=0;await load()})"><label>员工编号（用户ID）<input v-model="filters.userId" type="number" min="1" /></label><label>部门ID<input v-model="filters.departmentId" type="number" min="1" /></label><label>资产ID<input v-model="filters.assetId" type="number" min="1" /></label><button :disabled="busy">查询领用记录</button></form>
 <DataTable :rows="rows.map(r=>({...r,status_text:states[r.status||r.asset_status],kind_text:r.kind==='ITEM'?'按件':'消耗品',return_text:r.return_requested?'待确认归还':r.returned_at?'已归还':''}))" :columns="cols(tab==='ledger'?['id:资产ID','code:资产编号','name:名称','category_name:分类','kind_text:管理方式','unit:单位','stock:库存数量','status_text:状态','enabled:启用']:tab==='categories'?['name:分类名称','kind_text:管理方式','enabled:启用']:tab==='holdings'?['code:资产编号','name:资产名称','category_name:分类','issued_at:领用时间','returned_at:归还时间']:['id:申请编号','user_name:申请人','department_name:部门','category_name:分类','consumable_name:消耗品','quantity:数量','status_text:状态','return_text:归还登记','expected_date:期望领用日期'])">
 <template #actions="{row}"><template v-if="tab==='ledger'"><button :disabled="busy" @click="edit('asset',row)">编辑资产</button><button :disabled="busy" @click="edit('adjust',row)">调整库存或状态</button><button v-if="row.attachment_id" @click="run(()=>download('/attachments/'+row.attachment_id,'资产附件'))">下载附件</button></template><button v-else-if="tab==='categories'" @click="edit('category',row)">编辑分类</button><button v-else @click="run(()=>open(row.request_id||row.id))">查看领用</button></template>
 </DataTable>
 <div class="pagination"><span>第 {{offset/200+1}} 页</span><button :disabled="busy||!offset" @click="run(async()=>{offset-=200;await load()})">上一页</button><button :disabled="busy||rows.length<200||tab==='categories'" @click="run(async()=>{offset+=200;await load()})">下一页</button></div>
 </template>
</section>
<div v-if="editor||detail" class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-label="资产领用详情与维护"><div class="modal-header"><h2>{{editor==='asset'?'资产登记':editor==='category'?'资产分类':editor==='adjust'?'调整库存或状态':'领用详情'}}</h2><button @click="editor='';detail=null">关闭资产窗口</button></div><p v-if="error" role="alert" class="error">{{error}}</p>
 <form v-if="editor" @submit.prevent="run(save)">
 <template v-if="editor==='category'"><label>分类名称<input v-model="form.name" required maxlength="100" /></label><label>管理方式<select v-model="form.kind" :disabled="!!editId"><option value="ITEM">按件</option><option value="CONSUMABLE">消耗品</option></select></label></template>
 <template v-if="editor==='asset'"><label>资产分类<select aria-label="资产分类" v-model="form.categoryId" required :disabled="!!editId"><option v-for="c in categories" :key="c.id" :value="c.id">{{c.name}}{{c.enabled?'':'（已停用）'}}</option></select></label><label>资产编号（按件必填）<input v-model="form.code" maxlength="100" /></label><label>资产名称<input v-model="form.name" required maxlength="100" /></label><label>品牌型号<input v-model="form.brand" maxlength="200" /></label><label>序列号<input v-model="form.serialNumber" maxlength="200" /></label><label>购入日期<input v-model="form.purchaseDate" type="date" /></label><label>存放位置<input v-model="form.location" maxlength="200" /></label><label>单位（消耗品必填）<input v-model="form.unit" maxlength="30" /></label><label>备注<textarea v-model="form.note" maxlength="2000" /></label><label>可选图片/PDF附件（≤10MB）<input type="file" accept=".pdf,.png,.jpg,.jpeg" @change="run(()=>upload($event))" /></label><p v-if="form.attachmentId">已关联附件 <button type="button" @click="form.attachmentId=null">移除附件</button></p><p>新增消耗品库存为0，请在登记后用库存调整入库并填写原因。</p></template>
 <template v-if="editor==='adjust'"><label v-if="form.kind==='CONSUMABLE'">库存增减数量（入库正数，调减负数）<input v-model.number="form.delta" type="number" required /></label><label v-else>调整为<select v-model="form.status"><option value="AVAILABLE">在库</option><option value="REPAIR">维修中</option><option value="SCRAPPED">已报废</option></select></label><label>调整原因<input v-model="form.reason" required maxlength="120" /></label></template>
 <label v-else><input v-model="form.enabled" type="checkbox" />启用</label><button :disabled="busy" class="primary">保存资产信息</button>
 </form>
 <template v-else-if="detail"><h3>{{detail.category_name}} · {{detail.user_name}}</h3><p>申请 #{{detail.id}} · {{states[detail.status]}} · 数量 {{detail.quantity}}</p><p>用途：{{detail.purpose}}</p><p>期望领用：{{detail.expected_date}} · {{detail.needs_return?'需要归还':'无需归还'}} · 预计归还：{{detail.return_date||'未填写'}}</p><p>发放时间：{{display(detail.issued_at)}} · 归还时间：{{display(detail.returned_at)}}</p><p>实际审批人：{{detail.approval_actor_name||'未处理/历史记录'}} · 审批说明：{{detail.approval_note||'无'}} · 发放说明：{{detail.issue_note||'无'}} · 归还说明：{{detail.return_note||'无'}}</p><p v-if="detail.return_requested">已登记归还，等待行政确认。</p><ul><li v-for="a in detail.assignments" :key="a.asset_id">{{a.code}} · {{a.name}} · {{a.returned_at?'已归还':'当前领用'}}</li></ul>
 <label>处理说明（驳回、拒绝发放和确认归还必填）<textarea v-model="note" maxlength="1000" /></label>
 <p v-for="h in detail.reassignments" :key="'reassign'+h.id">管理员 {{h.actor_name}} 改派：{{h.previous_approver_name||'财务或管理员'}} → {{h.approver_name}} · {{h.note}} · {{h.created_at}}</p><ApprovalAdmin v-if="me?.roles.split(',').includes('ADMIN') && detail.status==='PENDING'" :path="'/assets/requests/'+detail.id" :owner-id="detail.user_id"  @done="run(async()=>{await open(detail!.id);await load()})" /><div class="actions" v-if="detail.canApprove"><button :disabled="busy" @click="run(()=>act('approve',{approved:true,note}))">同意领用</button><button :disabled="busy" @click="run(()=>act('approve',{approved:false,note}))">驳回领用</button></div>
 <button v-if="detail.isOwner&&['PENDING','READY'].includes(detail.status)" :disabled="busy" @click="run(()=>act('withdraw'))">撤回领用</button>
 <template v-if="detail.canManage&&detail.status==='READY'"><fieldset v-if="detail.kind==='ITEM'"><legend>选择在库资产编号（{{detail.quantity}} 件）</legend><label v-for="a in available" :key="a.id"><input v-model="selected" type="checkbox" :value="a.id" />{{a.code}} · {{a.name}}</label><p v-if="!available.length">没有可发放资产</p></fieldset><p v-else>确认后按申请数量扣减库存，库存不足将拒绝。</p><button :disabled="busy" @click="run(()=>act('issue',{assetIds:selected,note}))">确认发放</button><button :disabled="busy" @click="run(()=>act('issue',{rejected:true,note}))">拒绝发放</button></template>
 <template v-if="detail.kind==='ITEM'&&['IN_USE','ISSUED'].includes(detail.status)"><button v-if="detail.isOwner&&!detail.return_requested" :disabled="busy" @click="run(()=>act('return',{note}))">发起归还登记</button><template v-if="detail.canManage"><label><input v-model="repair" type="checkbox" />收回后登记为维修中</label><button :disabled="busy" @click="run(()=>act('confirm-return',{note,repair}))">确认收回资产</button></template></template>
 </template>
</section></div>
</template>
