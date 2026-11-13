<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { api, download, display, type Actor, type Row } from './api';
import UrgeButton from './UrgeButton.vue';
import {uploadStatus} from './upload';
import DataTable from './DataTable.vue';
const props = defineProps<{me: Actor; requestId?: number;initialDraft?:Row}>();
const emit = defineEmits<{submitted:[id:number]; close:[]; changed:[];copy:[draft:Row]}>();
const busy = ref(false), error = ref(''), detail = ref<Row | null>(null), types = ref<Row[]>([]), colleagues = ref<Row[]>([]), policy = ref<Row>({});
const files = ref<Row[]>([]), approval = reactive({approve:true,comment:''});
const fresh = () => ({ kind:'LEAVE',typeId:null as number|null,startDate:'',startTime:'09:00',endDate:'',endTime:'18:00',correctionAt:'',punchKind:'NORMAL',amount:0,reason:'',items:[{description:'',amount:0}],ccUserIds:[] as number[] });
const application = reactive(fresh());
const templates=ref<Row[]>([]),selectedTemplate=ref(''),templateName=ref(''),notice=ref(''),draftWarnings=ref<string[]>([]);
async function loadTemplates(){templates.value=await api('/request-templates')}
function fill(draft:Row){Object.assign(application,fresh(),draft.payload||draft,{kind:draft.kind||application.kind,startDate:'',endDate:''});files.value=[];draftWarnings.value=(draft.warnings||[]).filter((w:string)=>w!=='请重新选择日期，附件不会复制');notice.value='请重新选择日期，附件不会复制';}
watch(()=>props.initialDraft,draft=>{if(draft)fill(draft)},{immediate:true});
async function useTemplate(){const t=templates.value.find(t=>t.id===Number(selectedTemplate.value));if(t)fill(t)}
async function saveTemplate(){if(!templateName.value.trim())throw new Error('请填写模板名称');const {typeId,reason,items,amount,ccUserIds,startTime,endTime}=application;await api('/request-templates','POST',{name:templateName.value,kind:application.kind,payload:{typeId,reason,items,amount,ccUserIds,startTime,endTime,...(application.startDate&&application.endDate?{durationDays:(Date.parse(application.endDate)-Date.parse(application.startDate))/86400000+(endTime==='18:00' ? 1 : 0.5)-(startTime==='13:00' ? 0.5 : 0)}:{})}});await loadTemplates();notice.value='模板已保存'}

const cols = (pairs:string[]) => pairs.map(p=>{const [key,label]=p.split(':');return {key:key!,label:label!}});
const canApprove = computed(()=>detail.value?.status==='PENDING' && detail.value.user_id!==props.me.id && detail.value.steps.some((s:Row)=>s.step===detail.value!.current_step && s.approver_id===props.me.id));
// Interpret backend local timestamps in Asia/Shanghai even if the browser uses another timezone.
const canCancel = computed(()=>detail.value?.user_id===props.me.id && (detail.value.status==='PENDING' || (detail.value.status==='APPROVED' && !['EXPENSE','CORRECTION'].includes(detail.value.kind) && Date.parse(/(?:Z|[+-]\d{2}:\d{2})$/.test(detail.value.start_at) ? detail.value.start_at : detail.value.start_at+'+08:00')>Date.now())));
async function run(action:()=>Promise<void>) {
  if(busy.value)return; busy.value=true;error.value='';
  try{await action()}catch(e){error.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}
}
async function loadDetail(){ detail.value=await api('/requests/'+props.requestId); }
onMounted(()=>run(async()=>{
  if(props.requestId) await loadDetail();
  else { [types.value,colleagues.value,policy.value]=await Promise.all([api('/leave-types'),api('/colleagues'),api('/attendance-policy')]);await loadTemplates();}
}));
watch(()=>props.requestId,()=>{if(props.requestId)run(loadDetail)});
watch(()=>application.kind,()=>{files.value=[]});
async function upload(event:Event){
  const input=event.target as HTMLInputElement;
  await run(async()=>{
    const picked=Array.from(input.files||[]);
    for(const [i,file] of picked.entries()){
      if(application.kind==='EXPENSE' && files.value.length>=30)throw new Error('票据最多30张');
      const body=new FormData();body.append('file',file);body.append('uploadIndex',String(i+1));body.append('uploadTotal',String(picked.length));
      const uploaded=await api('/attachments','POST',body);
      if(application.kind==='EXPENSE')files.value.push(uploaded);else files.value=[uploaded];
    }
  });input.value='';
}
async function submit(){await run(async()=>{
  const a=application;
  const body:Row={kind:a.kind,reason:a.reason,ccUserIds:a.ccUserIds};
  if(a.kind==='EXPENSE')Object.assign(body,{amount:a.amount,items:a.items,attachmentIds:files.value.map(f=>f.id)});
  else {
    body.attachmentId=files.value[0]?.id || null;
    if(a.kind==='CORRECTION')Object.assign(body,{startAt:a.correctionAt+':00',punchKind:a.punchKind});
    else Object.assign(body,{startAt:a.startDate+'T'+a.startTime+':00',endAt:a.endDate+'T'+a.endTime+':00',...(a.kind==='LEAVE'?{typeId:a.typeId}:{})});
  }
  const result=await api('/requests','POST',body);Object.assign(application,fresh());files.value=[];emit('submitted',result.id);
})}
async function decide(){await run(async()=>{await api('/requests/'+props.requestId+'/decision','POST',approval);await loadDetail();emit('changed')})}
async function cancel(){await run(async()=>{await api('/requests/'+props.requestId+'/cancel','POST');await loadDetail();emit('changed')})}
</script>
<template>
  <section v-if="!requestId" class="card staff-request">
    <h2>新建申请</h2><p class="muted">审批链、天数和余额均由服务端按现有规则核验。全部时间为北京时间。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" class="success" role="status">{{notice}}</p><p v-for="warning in draftWarnings" :key="warning" class="error">{{warning}}</p><p v-if="uploadStatus" role="status">{{uploadStatus}}</p>
    <div class="card"><label>从模板填单<select v-model="selectedTemplate" :disabled="busy" @change="run(useTemplate)"><option value="">请选择常用模板</option><option v-for="t in templates" :key="t.id" :value="String(t.id)">{{t.name}}（{{display(t.kind)}}）</option></select></label><label>模板名称<input v-model="templateName" maxlength="60" :disabled="busy" /></label><button :disabled="busy||!['LEAVE','TRAVEL','OUTING','EXPENSE'].includes(application.kind)" @click="run(saveTemplate)">保存为常用模板</button><details><summary>模板管理</summary><p v-for="t in templates" :key="t.id"><input v-model="t.name" aria-label="重命名模板" maxlength="60" /><button :disabled="busy" @click="run(async()=>{await api('/request-templates/'+t.id,'PUT',{name:t.name,kind:t.kind,payload:t.payload});await loadTemplates()})">重命名</button><button :disabled="busy" @click="run(async()=>{await api('/request-templates/'+t.id,'DELETE');await loadTemplates()})">删除模板</button></p></details></div>
    <form @submit.prevent="submit"><fieldset :disabled="busy" class="staff-fields">
      <label>申请类型<select aria-label="申请类型" v-model="application.kind"><option value="LEAVE">请假</option><option value="TRAVEL">出差</option><option value="OUTING">外出</option><option value="EXPENSE">报销</option><option value="OVERTIME">加班</option><option value="CORRECTION">补卡</option></select></label>
      <label v-if="application.kind==='LEAVE'">假期类型<select aria-label="假期类型" v-model="application.typeId" required><option :value="null" disabled>请选择</option><option v-for="t in types" :key="t.id" :value="t.id">{{ t.name }}{{ t.proof_required?'（必须证明）':t.code==='SICK'?'（建议证明）':'' }}</option></select></label>
      <template v-if="application.kind==='CORRECTION'"><label>补卡类型<select aria-label="补卡类型" v-model="application.punchKind"><option value="NORMAL">正常打卡</option><option value="FIELD">出勤</option></select></label><label>补卡时间（北京时间）<input v-model="application.correctionAt" type="datetime-local" required /></label><p class="muted">仅补过去时间，提交期限为补卡日期后 {{ policy.correction_days }} 个自然日内；出勤须在已批准出差/外出时段内。批准后不可撤销。</p></template>
      <template v-else-if="application.kind!=='EXPENSE'"><div class="form-grid"><label>开始日期<input v-model="application.startDate" type="date" required /></label><label>开始时间<select aria-label="开始时间" v-model="application.startTime"><option>09:00</option><option>13:00</option></select></label><label>结束日期<input v-model="application.endDate" type="date" required /></label><label>结束时间<select aria-label="结束时间" v-model="application.endTime"><option>13:00</option><option>18:00</option></select></label></div><p class="muted">自然日半天模型，含周末。请假、出差、外出跨年须拆单；加班可跨年，最终批准计入开始年度调休。</p><p v-if="application.kind==='OVERTIME'" class="muted">当前兑换比例 {{ policy.overtime_ratio }}；超过 {{ policy.leave_threshold }} 天追加部门负责人（按既有去重规则）。撤销需尚未开始且调休余额足够退回。</p></template>
      <template v-else><label>报销总额（元）<input v-model.number="application.amount" type="number" min="0.01" max="99999999" step="0.01" required /></label><h3>费用明细</h3><div v-for="(item,i) in application.items" :key="i" class="staff-line"><label>用途 / 类别<input v-model="item.description" maxlength="200" required /></label><label>明细金额（元）<input v-model.number="item.amount" type="number" min="0.01" max="99999999" step="0.01" required /></label><button type="button" :disabled="application.items.length===1" @click="application.items.splice(i,1)">删除明细</button></div><button type="button" :disabled="application.items.length>=100" @click="application.items.push({description:'',amount:0})">添加明细</button><p class="muted">明细合计须等于总额；最多100行，至少一张票据。服务端精确核算金额。</p></template>
      <label>申请理由<textarea v-model="application.reason" required maxlength="2000" /></label>
      <label>{{ application.kind==='EXPENSE'?'票据附件（必填，可多张）':'证明文件（可选，假期要求证明时必填）' }}<input type="file" :multiple="application.kind==='EXPENSE'" accept=".pdf,.png,.jpg,.jpeg" @change="upload" /></label><p class="muted">PDF / PNG / JPEG，每份最多10MB；报销最多30张。</p><p v-for="(f,i) in files" :key="f.id">{{ f.name }} <button type="button" @click="files.splice(i,1)">移除附件</button></p>
      <label>抄送同事（可多选）<select v-model="application.ccUserIds" multiple><option v-for="c in colleagues" :key="c.id" :value="c.id">{{ c.name }}（{{ c.employee_no }} · {{ c.department_name }}）</option></select></label><p class="muted">电脑按 Ctrl / Command 多选；手机点选。最多100位。</p>
      <button class="primary" type="submit">{{ busy?'正在处理…':'提交申请' }}</button>
    </fieldset></form>
  </section>
  <div v-else class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-label="申请详情"><div class="modal-header"><h2>申请单 #{{ requestId }}</h2><button aria-label="关闭申请详情" @click="emit('close')">×</button></div><p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="!detail">正在加载申请…</p>
    <template v-if="detail"><UrgeButton :detail="detail" :path="'/requests/'+detail.id" @done="run(loadDetail)" /><button v-if="detail.user_id===me.id&&['LEAVE','TRAVEL','OUTING','EXPENSE'].includes(detail.kind)" :disabled="busy" @click="run(async()=>emit('copy',await api('/requests/'+detail!.id+'/copy')))">复制再发起</button><dl class="detail-grid"><div><dt>申请人</dt><dd>{{ detail.user_name }}</dd></div><div><dt>类型 / 状态</dt><dd>{{ display(detail.kind) }} / {{ display(detail.status) }}</dd></div><div><dt>开始时间（北京时间）</dt><dd>{{ display(detail.start_at) }}</dd></div><div><dt>结束时间（北京时间）</dt><dd>{{ display(detail.end_at) }}</dd></div><div><dt>天数 / 金额</dt><dd>{{ display(detail.days) }} 天 / {{ display(detail.amount) }} 元</dd></div><div><dt>申请理由</dt><dd class="announcement-body">{{ detail.reason }}</dd></div></dl>
      <button v-if="detail.attachment_id" :disabled="busy" @click="run(()=>download('/attachments/'+detail!.attachment_id,'证明文件'))">下载证明</button>
      <p v-if="detail.kind==='CORRECTION'">补卡类型：{{ display(detail.punch_kind) }}；批准后生成补卡记录，不含现场定位。</p><p v-if="detail.kind==='OVERTIME'">兑换比例：{{ detail.overtime_ratio }}；批准计入 {{ detail.credited_days }} 天调休。</p>
      <template v-if="detail.kind==='EXPENSE'"><h3>费用明细</h3><DataTable :rows="detail.items" :columns="cols(['description:用途 / 类别','amount:金额（元）'])" /><h3>票据附件</h3><p v-if="!detail.attachments.length">历史单据未上传票据</p><button v-for="f in detail.attachments" :key="f.id" :disabled="busy" @click="run(()=>download('/attachments/'+f.id,f.original_name))">下载：{{ f.original_name }}</button><h3>支付状态：{{ display(detail.payment_status) }}</h3><ol class="timeline"><li v-for="h in detail.payment_history" :key="h.id">{{ h.actor_name }} · {{ display(h.status) }} · {{ display(h.acted_at) }}<p>{{ h.reason }}</p></li></ol></template>
      <h3>抄送名单</h3><p>{{ detail.cc.map((c:Row)=>c.name+'（'+c.employee_no+'）').join('、') || '无' }}</p><h3>审批记录</h3><p v-for="h in detail.reassignments" :key="h.id">管理员 {{h.actor_name}} 改派：{{h.previous_approver_name}} → {{h.approver_name}} · {{h.note}} · {{display(h.created_at)}}</p><ol class="timeline"><li v-for="s in detail.steps" :key="s.step">{{ s.approver_name }} · {{ display(s.status) }}<p>{{s.actor_name ? '实际操作：'+s.actor_name+' · ' : ''}}{{ s.comment || '暂无意见' }} · {{ display(s.acted_at) }}</p></li></ol>
      <form v-if="canApprove" @submit.prevent="decide"><label>审批结果<select v-model="approval.approve"><option :value="true">通过</option><option :value="false">驳回</option></select></label><label>审批意见（驳回必填）<textarea v-model="approval.comment" maxlength="1000" :required="!approval.approve" /></label><button class="primary" :disabled="busy">提交审批</button></form>
      <div v-if="canCancel" class="cancel-area"><p class="muted">待批可撤回；已批仅尚未开始可撤销，报销和补卡批准后不可撤销。加班撤销由服务端核验调休退回额度。</p><button class="danger" :disabled="busy" @click="cancel">{{ detail.status==='PENDING'?'撤回申请':'撤销申请' }}</button></div>
    </template>
  </section></div>
</template>
