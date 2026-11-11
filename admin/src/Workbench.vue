<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { api, type Row } from './api';
import DataTable from './DataTable.vue';
const props=defineProps<{company?:boolean}>();
const data=ref<Row|null>(null), messages=ref<Row|null>(null), error=ref(''), busy=ref(false);
const filters=reactive({from:'',to:'',scope:props.company?'team':'self',departmentId:''});
async function load(){
  busy.value=true;error.value='';
  try {
    const q=new URLSearchParams({scope:filters.scope});
    for(const key of ['from','to','departmentId'] as const) if(filters[key])q.set(key,filters[key]);
    const result=await api('/workbench/summary?'+q);
    members.value=[]; records.value=null; selected.value=''; offset.value=0;
    data.value=result;filters.from=result.from;filters.to=result.to;
    messages.value=await api('/messages/summary');
  }catch(e){data.value=null;error.value=e instanceof Error?e.message:'汇总加载失败'}finally{busy.value=false}
}
onMounted(load);
const members=ref<Row[]>([]), records=ref<Row[]|null>(null), selected=ref(''), search=ref(''), offset=ref(0), recordOffset=ref(0);
async function findMembers(){
  try {members.value=await api('/workbench/members?'+new URLSearchParams({search:search.value,offset:String(offset.value),...(filters.departmentId?{departmentId:filters.departmentId}:{})}));}
  catch(e){error.value=e instanceof Error?e.message:'加载员工失败'}
}
async function punches(){
  try { records.value=await api('/punches?'+new URLSearchParams({userId:selected.value,from:data.value!.from,to:data.value!.to,offset:String(recordOffset.value)})); }
  catch(e){error.value=e instanceof Error?e.message:'加载记录失败'}
}
const attendance=[['normalPunches','正常打卡次数'],['fieldPunches','出勤打卡次数'],['recordedDays','有打卡记录天数（人日）'],['approvedCorrections','已批准补卡次数'],['leaveDays','请假天数'],['travelDays','出差天数'],['outingDays','外出天数'],['overtimeDays','加班天数'],['overtimeCreditedDays','加班折算调休天数']];
const cols=(pairs:string[])=>pairs.map(p=>{const [key,label]=p.split(':');return {key:key!,label:label!}});
</script>
<template>
<section class="card workbench" aria-label="考勤与审批汇总">
  <h2>考勤与审批汇总</h2>
  <p v-if="messages">待办 {{ messages.pending }} · 未读通知 {{ messages.notifications }} · 未读公告 {{ messages.announcements }}</p>
  <form class="filters" @submit.prevent="load">
    <label>汇总开始日期<input v-model="filters.from" type="date" required /></label>
    <label>汇总结束日期<input v-model="filters.to" type="date" required /></label>
    <label v-if="data?.canViewTeam">统计范围<select v-model="filters.scope" @change="filters.departmentId=''" aria-label="统计范围"><option value="self">本人</option><option value="team">{{ data.canFilterDepartment?'全公司':'团队' }}</option></select></label>
    <label v-if="data?.canFilterDepartment && filters.scope==='team'">统计部门（含下级）<select v-model="filters.departmentId" aria-label="统计部门"><option value="">全公司</option><option v-for="d in data.departments" :key="d.id" :value="String(d.id)">{{ d.name }}</option></select></label>
    <button class="primary" :disabled="busy">查询汇总</button>
  </form>
  <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="busy" role="status">正在加载汇总…</p>
  <template v-if="data">
    <p data-testid="workbench-range">{{ data.from }} 至 {{ data.to }} · {{ data.scope==='self'?'本人':'团队 / 公司' }} · 在职人数 {{ data.people }}</p>
    <div class="stats"><div v-for="[key,label] in attendance" :key="key" class="card"><span>{{ label }}</span><strong :data-testid="key">{{ data.attendance[key!] }}</strong></div></div>
    <h3>已批准的请假、出差、外出与加班</h3>
    <p>无记录为 0 天。只统计区间内的自然日和半天；含周末，加班调休按单据提交时的兑换比例折算。</p>
    <DataTable :rows="data.attendance.daysByType" :columns="cols(['kind:类型','leaveType:假种','days:天数','creditedDays:折算天数（加班为调休额度）'])" />
    <details @toggle="findMembers"><summary>按员工查看打卡记录</summary>
      <form class="filters" @submit.prevent="offset=0;findMembers()"><label>搜索员工<input v-model="search" maxlength="100" /></label><button>查找员工</button></form>
      <button :disabled="!offset" @click="offset-=200;findMembers()">上一页员工</button><button :disabled="members.length<200" @click="offset+=200;findMembers()">下一页员工</button>
      <label>员工<select v-model="selected" aria-label="查看员工打卡" @change="recordOffset=0;punches()"><option value="" disabled>请选择员工</option><option v-for="u in members" :key="u.id" :value="String(u.id)">{{ u.name }}（{{ u.employee_no }}）</option></select></label>
      <DataTable v-if="records" :rows="records" :columns="cols(['punched_at:打卡时间（北京时间）','kind:类型','correction_request_id:补卡单号'])" />
      <template v-if="records"><button :disabled="!recordOffset" @click="recordOffset-=200;punches()">上一页记录</button><button :disabled="records.length<200" @click="recordOffset+=200;punches()">下一页记录</button></template>
    </details>
    <h3>审批时效</h3>
    <p>当前待我审批 {{ data.approvals.pendingForMe }} · {{ data.scope==='self'?'我发起尚未办结':'范围内发起尚未办结' }} {{ data.approvals.initiatedPending }}</p>
    <p>统计期办结 {{ data.approvals.completed }} · 平均审批耗时 {{ data.approvals.averageHours }} 小时</p>
    <p>当前待审批 {{ data.approvals.waiting.total }}：1 天内 {{ data.approvals.waiting.withinOneDay }} · 1–3 天 {{ data.approvals.waiting.oneToThreeDays }} · 超过 3 天 {{ data.approvals.waiting.overThreeDays }}</p>
    <DataTable :rows="data.approvals.byType" :columns="cols(['kind:审批类型','status:结果','completed:办结数量','averageHours:平均耗时（小时）'])" />
    <p class="muted">北京时间，含首尾最多366天。待审批和等待分段为实时全部待审，不受日期限制；本人时效包含本人发起及待本人处理的申请，办结包含本人参与审批的申请。团队按申请人范围统计。仅六类审批，不含会议、任务、文档。打卡含批准补卡且不重复计数。可在现有打卡记录和消息中心查看本人记录。</p>
  </template>
</section>
</template>
<style scoped>
.workbench { min-width: 0; }
.workbench .stats { grid-template-columns: repeat(auto-fit, minmax(min(100%, 170px), 1fr)); margin: 18px 0; }
.workbench details { margin: 18px 0; }
.workbench summary { cursor: pointer; font-weight: 600; }
.workbench select { max-width: 100%; }
</style>
