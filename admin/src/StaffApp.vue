<script setup lang="ts">
import { validatePunchLocation } from './punchValidation';
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { api, display, setToken, type Actor, type Row } from './api';
import AttendanceMonthly from './AttendanceMonthly.vue';
import {useMessageSummary,approvalTotal} from './messageSummary';
import {uploadStatus} from './upload';
import DataTable from './DataTable.vue';
import MessageCenter from './MessageCenter.vue';
import StaffRequest from './StaffRequest.vue';
import Calendar from './Calendar.vue';
import Tasks from './Tasks.vue';
import Assets from './Assets.vue';
import Contracts from './Contracts.vue';
import Visitors from './Visitors.vue';
const contractId=ref<number|null>(null);
const assetRequestId=ref<number|null>(null);
import Documents from './Documents.vue';
import Workbench from './Workbench.vue';
const documentId = ref<number|null>(null);
const taskId = ref<number|null>(null);
const me = ref<Actor | null>(null), ready = ref(false), busy = ref(false), error = ref(''), message = ref('');
const {summary:counts,refresh:refreshCounts}=useMessageSummary(computed(()=>!!me.value&&!me.value.mustChange));
const draft=ref<Row>();
function copyDraft(value:Row){draft.value=value;detailId.value=null;page.value='apply';revision.value++}
const page = ref('apply'), passwordOpen = ref(false), detailId = ref<number | null>(null), revision = ref(0);
const meetingId = ref<number | null>(null);
const nav = [['visitors','访客管理'],['contracts','合同管理'],['assets','资产领用'],['documents','文档资料'],['tasks','待办任务'],['calendar','日程与会议'],['apply','提交申请'],['messages','消息中心'],['directory','通讯录'],['punches','打卡记录'],['profile','个人中心']];
const title = computed(() => nav.find(n => n[0] === page.value)?.[1]);
const login = reactive({ login: '', password: '', deviceName: '员工办公浏览器', platform: 'Web' });
const password = reactive({ oldPassword: '', newPassword: '' });
const profile = ref<Row>({}), balances = ref<Row[]>([]), history = ref<Row[]>([]), sessions = ref<Row[]>([]), departments = ref<Row[]>([]), rows = ref<Row[]>([]);
const phone = ref(''), offset = ref(0), filters = reactive({ search: '', departmentId: '', from: '', to: '', kind: '' });
const cols = (pairs: string[]) => pairs.map(p => { const [key,label] = p.split(':'); return { key: key!, label: label! }; });
const departmentTree = computed(() => {
  const result: Row[] = [];
  function walk(parent: number | null, depth: number) {
    for (const d of departments.value.filter(d => d.parent_id === parent)) {
      result.push({ ...d, label: '　'.repeat(depth) + d.name }); walk(d.id, depth + 1);
    }
  }
  walk(null, 0); return result;
});
async function run(action: () => Promise<void>, success = '') {
  if (busy.value) return;
  busy.value = true; error.value = ''; message.value = '';
  try { await action(); message.value = success; }
  catch(e) { error.value = e instanceof Error ? e.message : '操作失败'; }
  finally { busy.value = false; }
}
function clearSession() {
  setToken(''); me.value = null; passwordOpen.value = false; detailId.value = null;
  profile.value = {}; rows.value = []; sessions.value = []; balances.value = []; history.value = []; departments.value = [];
  password.oldPassword = ''; password.newPassword = ''; login.password = '';
}
function expired() { clearSession(); error.value = '会话已失效，请重新登录'; }
async function load() {
  if (page.value === 'directory') {
    departments.value = await api('/directory/departments');
    rows.value = await api('/directory?' + new URLSearchParams({ search: filters.search, offset: String(offset.value), ...(filters.departmentId ? {departmentId: filters.departmentId} : {}) }));
  } else if (page.value === 'punches') {
    rows.value = (await api<Row[]>('/punches?' + new URLSearchParams({ from: filters.from, to: filters.to, kind: filters.kind, offset: String(offset.value) }))).map(r => ({...r, location_source:r.location_source||'UNKNOWN', source: r.correction_request_id ? '补卡' : '现场打卡'}));
  } else if (page.value === 'profile') {
    [profile.value, balances.value, history.value, sessions.value] = await Promise.all([api('/profile'), api('/balances'), api('/balance-history'), api('/auth/sessions')]);
    phone.value = profile.value.work_phone || '';
  }
}
async function navigate(key: string) {
  await refreshCounts();
  if (key === 'contracts') contractId.value=null;
  if (key === 'assets') assetRequestId.value = null;
  await run(async () => { page.value = key; rows.value = []; offset.value = 0; await load(); });
}
async function restore() {
  me.value = (await api('/auth/me')).user;
  if (!me.value?.mustChange) { page.value = 'apply'; revision.value++; }
}
async function signIn() {
  await run(async () => { const data = await api('/auth/login', 'POST', login); setToken(data.token); login.password = ''; await restore(); });
}
async function changePassword() {
  await run(async () => { await api('/auth/password','POST',password); clearSession(); }, '密码已修改，所有旧会话已退出，请重新登录');
}
async function revoke(id: string, current = false) {
  await run(async () => { await api('/auth/sessions/'+id,'DELETE'); if(current) clearSession(); else sessions.value = await api('/auth/sessions'); }, '设备已退出');
}
onMounted(async () => {
  window.addEventListener('oa-session-expired', expired);
  if (sessionStorage.getItem('oa-staff-token')) await run(restore);
  ready.value = true;
});
onUnmounted(() => window.removeEventListener('oa-session-expired', expired));
const punchBusy=ref(false),punchMessage=ref('');
async function browserPunch(kind:string){
 punchBusy.value=true;punchMessage.value='正在获取现场实时定位…';
 try{
 const pos=await new Promise<GeolocationPosition>((resolve,reject)=>navigator.geolocation.getCurrentPosition(resolve,reject,{enableHighAccuracy:true,maximumAge:0,timeout:45000}));
 validatePunchLocation(pos.coords.accuracy,pos.timestamp);
 const locatedAt=new Date(pos.timestamp).toISOString();
 punchMessage.value='现场实时 · 采集时间 '+display(locatedAt)+'（北京时间）';
 if(!window.confirm(punchMessage.value+'，确认打卡？'))return;
 validatePunchLocation(pos.coords.accuracy,pos.timestamp);
 await api('/punches','POST',{kind,latitude:pos.coords.latitude,longitude:pos.coords.longitude,accuracy:pos.coords.accuracy,locatedAt,locationSource:'LIVE'});
 await load();punchMessage.value='打卡成功 · '+punchMessage.value;
 }catch(e){
 const code=e&&typeof e==='object'&&'code' in e?e.code:undefined;
 punchMessage.value=code===3?'定位超时，请到信号较好的地方重试':code===1?'定位权限被拒绝，请在浏览器设置中允许定位后重试':code===2?'当前位置不可用，请检查定位设置后重试':e&&typeof e==='object'&&'message' in e?String(e.message):'定位或打卡失败，请检查浏览器定位权限';
 }finally{punchBusy.value=false}
}
</script>
<template>
  <div v-if="!ready" class="first-password" role="status">正在恢复会话…</div>
  <div v-else-if="!me" class="login-layout staff-app">
    <section class="login-intro"><div class="brand-mark">OA</div><h1>员工办公</h1><p>申请、审批与消息，随时在这里处理。</p><small>公司办公系统 · 员工入口</small></section>
    <section class="login-panel"><form @submit.prevent="signIn"><h2>登录员工办公</h2><p class="muted">使用与手机 App 相同的员工账号</p><p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="message" role="status" class="success">{{ message }}</p><label>工号或手机号<input v-model="login.login" autocomplete="username" required /></label><label>密码<input v-model="login.password" type="password" autocomplete="current-password" required /></label><label>设备名称<input v-model="login.deviceName" maxlength="100" required /></label><button class="primary wide" :disabled="busy">登录员工办公</button><p class="muted">会话有效期 8 小时，可在个人中心按设备退出。</p></form></section>
  </div>
  <div v-else-if="me.mustChange" class="first-password staff-app"><section class="card"><h1>首次登录，请修改密码</h1><p>修改后需重新登录。</p><p v-if="error" role="alert" class="error">{{ error }}</p><form @submit.prevent="changePassword"><label>原密码<input v-model="password.oldPassword" type="password" autocomplete="current-password" required /></label><label>新密码（至少10位，最多72个 UTF-8 字节）<input v-model="password.newPassword" type="password" autocomplete="new-password" minlength="10" required /></label><button class="primary" :disabled="busy">保存并重新登录</button></form><button :disabled="busy" @click="run(async()=>{await api('/auth/logout','POST');clearSession()})">退出登录</button></section></div>
  <div v-else class="shell staff-app">
    <aside class="sidebar"><div class="brand"><span class="brand-mark">OA</span><span>员工办公<small>公司办公系统</small></span></div><nav aria-label="员工功能"><button v-for="[key,label] in nav" :key="key" :class="{active:page===key}" :aria-current="page===key?'page':undefined" :disabled="busy" @click="navigate(key!)">{{ label }}</button></nav><div class="sidebar-foot">与手机 App 共用账号与业务数据</div></aside>
    <main><header><h1>{{ title }}</h1><div class="user-bar"><span>{{ me.name }}</span><button :disabled="busy" @click="passwordOpen=true">修改密码</button><button :disabled="busy" @click="run(async()=>{await api('/auth/logout','POST');clearSession()})">退出登录</button></div></header>
      <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="message" role="status" class="success">{{ message }}</p>
      <p v-if="uploadStatus" role="status">{{uploadStatus}}</p><p v-if="!['apply','messages'].includes(page)">待审批 {{approvalTotal(counts)}} · 待办任务 {{counts.tasks||0}} · 未读通知 {{counts.notifications||0}} · 未读公告 {{counts.announcements||0}}</p>
      <AttendanceMonthly v-if="page==='punches'" :me="me" self @correct="copyDraft" />
      <Workbench v-if="page==='apply'" />
      <StaffRequest @copy="copyDraft" v-if="page==='apply'" :key="revision" :me="me" :initial-draft="draft" @submitted="id=>{detailId=id;message='申请已提交'}" />
      <Documents v-if="page==='documents'" :document-id="documentId" />
      <Visitors v-if="page==='visitors'" :me="me" employee />
      <Contracts v-if="page==='contracts'" :me="me" :contract-id="contractId" />
      <Assets v-if="page==='assets'" :me="me" :request-id="assetRequestId" />
      <Tasks v-if="page==='tasks'" :me="me" :task-id="taskId" />
      <Calendar v-if="page==='calendar'" :me="me" :meeting-id="meetingId" />
      <MessageCenter @open-contract="id=>{contractId=id;page='contracts'}" @open-asset="id=>{assetRequestId=id;page='assets'}" @open-document="id=>{documentId=id;page='documents'}" @open-task="id=>{taskId=id;page='tasks'}" @open-meeting="id=>{meetingId=id;page='calendar'}" v-if="page==='messages'" :refresh-key="revision" :departments="[]" @open-request="row=>detailId=row.id" />
      <section v-if="page==='directory' || page==='punches'" class="card">
        <p v-if="page==='punches'" class="success">正常打卡须在公司网络，外勤须有有效出差/外出审批。网页使用现场实时定位，提交前显示采集时间。</p><div v-if="page==='punches'"><button :disabled="punchBusy" @click="browserPunch('NORMAL')">网页正常打卡</button><button :disabled="punchBusy" @click="browserPunch('FIELD')">网页外勤打卡</button><p role="status">{{punchMessage}}</p></div>
        <form class="filters" @submit.prevent="run(async()=>{offset=0;await load()})">
          <template v-if="page==='directory'"><label>搜索姓名 / 工号 / 部门<input v-model="filters.search" maxlength="100" /></label><label>部门（含下级）<select v-model="filters.departmentId"><option value="">全部部门</option><option v-for="d in departmentTree" :key="d.id" :value="String(d.id)">{{ d.label }}</option></select></label></template>
          <template v-else><label>开始日期<input v-model="filters.from" type="date" /></label><label>结束日期<input v-model="filters.to" type="date" /></label><label>打卡类型<select v-model="filters.kind"><option value="">全部类型</option><option value="NORMAL">正常打卡</option><option value="FIELD">外勤</option></select></label></template>
          <button class="primary" :disabled="busy">查询</button>
        </form>
        <DataTable :rows="rows" :columns="cols(page==='directory'?['name:姓名','employee_no:工号','department_name:部门','job_title:职务','work_phone:工作电话']:['punched_at:打卡时间（北京时间）','located_at:定位采集时间','location_source:定位来源','kind:类型','source:来源','correction_request_id:补卡单号'])" />
        <div class="pagination"><span>第 {{ offset/200+1 }} 页 · 每页最多200条</span><button :disabled="busy || !offset" @click="run(async()=>{offset-=200;await load()})">上一页</button><button :disabled="busy || rows.length<200" @click="run(async()=>{offset+=200;await load()})">下一页</button></div>
      </section>
      <section v-if="page==='profile'" class="card">
        <div class="section-toolbar"><h2>个人资料与假期余额</h2><button :disabled="busy" @click="run(load)">刷新</button></div>
        <p>{{ profile.name }} · {{ profile.employee_no }} · {{ profile.department_name }} · {{ profile.job_title || '未填写职务' }}</p>
        <form class="filters" @submit.prevent="run(async()=>{await api('/profile/work-phone','PUT',{workPhone:phone});await load()},'工作电话已更新')"><label>工作电话<input v-model="phone" maxlength="40" /></label><button :disabled="busy">保存工作电话</button></form>
        <DataTable :rows="balances" :columns="cols(['type_name:假期','year:年度','amount:剩余天数'])" />
        <h3>余额流水（最新500条）</h3><DataTable :rows="history" :columns="cols(['type_name:假期','year:年度','delta:变动天数','balance_after:变动后余额','reason:原因','request_id:关联单号','created_at:时间'])" />
        <h3>有效登录设备</h3><p>会话固定 8 小时；改密使所有旧会话失效。</p><button :disabled="busy" @click="revoke('others')">退出其他设备</button>
        <article v-for="s in sessions" :key="s.id" class="staff-device"><p>{{ s.device_name }} · {{ s.platform }}{{ s.current?'（当前设备）':'' }}</p><p class="muted">登录：{{ display(s.created_at) }} · 到期：{{ display(s.expires_at) }}</p><button :disabled="busy" @click="revoke(s.id,s.current)">退出此设备</button></article>
      </section>
    </main>
    <StaffRequest @copy="copyDraft" v-if="detailId" :me="me" :request-id="detailId" @close="detailId=null" @changed="revision++" />
    <div v-if="passwordOpen" class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-label="修改密码"><div class="modal-header"><h2>修改密码</h2><button aria-label="关闭修改密码" @click="passwordOpen=false">×</button></div><p v-if="error" role="alert" class="error">{{ error }}</p><form @submit.prevent="changePassword"><label>原密码<input v-model="password.oldPassword" type="password" autocomplete="current-password" required /></label><label>新密码（至少10位，最多72个 UTF-8 字节）<input v-model="password.newPassword" type="password" autocomplete="new-password" minlength="10" required /></label><button class="primary" :disabled="busy">保存并重新登录</button></form></section></div>
  </div>
</template>
