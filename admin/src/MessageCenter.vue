<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { api, download, display, type Row } from './api';
import DataTable from './DataTable.vue';
const props = defineProps<{ management?: boolean; departments: Row[]; refreshKey?: number }>();
const emit = defineEmits<{ openRequest: [row: Row] }>();
const categories = [ ['pending','待我审批'], ['initiated','我发起的'], ['cc','抄送我的'], ['notifications','系统通知（站内通知）'], ['announcements','公告'] ];
const category = ref('pending'), rows = ref<Row[]>([]), summary = ref<Row>({}), offset = ref(0);
const busy = ref(false), error = ref(''), editing = ref(false), detail = ref<Row | null>(null), receipts = ref<Row | null>(null);
const title = ref(''), body = ref(''), departmentId = ref<number | null>(null), files = ref<Row[]>([]);
const columns = (pairs: string[]) => pairs.map(p => { const [key,label]=p.split(':'); return {key:key!,label:label!}; });
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value=true; error.value='';
  try { await action(); } catch(e) { error.value=e instanceof Error ? e.message : '操作失败'; } finally { busy.value=false; }
}
async function load() {
  summary.value=await api('/messages/summary');
  rows.value=await api(props.management ? '/announcements?management=true&offset='+offset.value : '/messages?category='+category.value+'&offset='+offset.value);
}
async function open(row: Row) {
  await run(async () => {
    receipts.value=null;
    detail.value=await api('/announcements/'+row.id);
    if(props.management) receipts.value=await api('/announcements/'+row.id+'/receipts');
    await load();
  });
}
async function upload(event: Event) {
  const input=event.target as HTMLInputElement;
  await run(async () => {
    for(const file of Array.from(input.files || [])) {
      if(files.value.length >= 30) throw new Error('最多上传 30 份附件');
      const form=new FormData(); form.append('file',file);
      files.value.push(await api('/attachments','POST',form));
    }
  });
  input.value='';
}
async function publish() {
  await run(async () => {
    await api('/announcements','POST',{title:title.value,body:body.value,departmentId:departmentId.value,attachmentIds:files.value.map(f=>f.id)});
    editing.value=false; title.value=''; body.value=''; files.value=[]; departmentId.value=null;
    offset.value=0; await load();
  });
}
async function withdraw(row: Row) {
  await run(async () => {
    await api('/announcements/'+row.id+'/withdraw','POST');
    if(detail.value?.id===row.id) detail.value=await api('/announcements/'+row.id);
    await load();
  });
}
onMounted(() => run(load));
watch(() => props.refreshKey, () => run(load));
</script>
<template>
  <section class="card records">
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div class="section-toolbar">
      <p v-if="!management">待办 {{ summary.pending || 0 }} · 未读系统通知 {{ summary.notifications || 0 }} · 未读公告 {{ summary.announcements || 0 }}</p>
      <p v-else>发布时保存接收人快照；撤回后员工不可见，回执继续保留。</p>
      <div class="actions"><button :disabled="busy" @click="run(load)">刷新</button><button v-if="management" class="primary" @click="editing=true">发布公告</button></div>
    </div>
    <div v-if="!management" class="actions" role="tablist" aria-label="消息分类">
      <button v-for="[key,label] in categories" :key="key" role="tab" :aria-selected="category===key" :disabled="busy" @click="run(async()=>{category=key!;offset=0;await load()})">{{ label }}</button>
    </div>
    <DataTable :rows="rows" :columns="columns(management ? ['title:标题','published_at:发布时间','withdrawn_at:撤回时间','read_count:已读人数','unread_count:未读人数'] : category==='announcements' ? ['title:标题','published_at:发布时间','read_at:已读时间'] : category==='notifications' ? ['message:通知内容','created_at:时间','read_at:已读时间'] : ['id:单号','user_name:申请人','kind:类型','status:状态','created_at:提交时间'])">
      <template #actions="{ row }">
        <template v-if="management || category==='announcements'">
          <button @click="open(row)">{{ management ? '查看公告与回执' : '打开公告' }}</button>
          <button v-if="!management && !row.read_at" :disabled="busy" @click="run(async()=>{await api('/announcements/'+row.id+'/read','POST');await load()})">标记已读</button>
          <button v-if="management && !row.withdrawn_at" class="danger" :disabled="busy" @click="withdraw(row)">撤回公告</button>
        </template>
        <template v-else-if="category==='notifications'">
          <button v-if="!row.read_at" @click="run(async()=>{await api('/notifications/'+row.id+'/read','POST');await load()})">标为已读</button>
          <button v-if="row.request_id" @click="emit('openRequest',{id:row.request_id})">查看单据</button>
        </template>
        <button v-else @click="emit('openRequest',row)">查看单据</button>
      </template>
    </DataTable>
    <div class="pagination"><span>第 {{ offset/200+1 }} 页 · 每页最多 200 条</span><button :disabled="busy || offset===0" @click="run(async()=>{offset-=200;await load()})">上一页</button><button :disabled="busy || rows.length<200" @click="run(async()=>{offset+=200;await load()})">下一页</button></div>
  </section>
  <div v-if="editing || detail" class="modal-backdrop">
    <section class="modal" role="dialog" aria-modal="true" :aria-label="editing ? '发布公告' : '公告详情'">
      <div class="modal-header"><h2>{{ editing ? '发布公告' : detail?.title }}</h2><button aria-label="关闭公告弹窗" @click="editing=false;detail=null">×</button></div>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <form v-if="editing" @submit.prevent="publish">
        <label>公告标题<input v-model="title" required maxlength="200" /></label>
        <label>公告正文<textarea v-model="body" required maxlength="50000" rows="8" /></label>
        <label>发布范围<select v-model="departmentId"><option :value="null">全员</option><option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}（含下级部门）</option></select></label>
        <label>公告附件（可多份，单份最多 10MB）<input type="file" multiple accept=".pdf,.png,.jpg,.jpeg" :disabled="busy" @change="upload" /></label>
        <p v-for="(f,i) in files" :key="f.id">{{ f.name }} <button type="button" @click="files.splice(i,1)">移除附件</button></p>
        <button class="primary" :disabled="busy">确认发布</button>
      </form>
      <div v-else-if="detail">
        <p>发布时间：{{ display(detail.published_at) }} · {{ detail.withdrawn_at ? '已撤回' : '已发布' }}</p>
        <p class="announcement-body">{{ detail.body }}</p>
        <button v-for="f in detail.attachments" :key="f.id" @click="run(()=>download('/attachments/'+f.id,f.original_name))">下载：{{ f.original_name }}</button>
        <template v-if="receipts">
          <h3>已读 {{ receipts.readCount }} 人 / 未读 {{ receipts.unreadCount }} 人</h3>
          <h4>已读人员</h4><DataTable :rows="receipts.read" :columns="columns(['name:姓名','employee_no:工号','department_name:发布时部门','read_at:已读时间'])" />
          <h4>未读人员</h4><DataTable :rows="receipts.unread" :columns="columns(['name:姓名','employee_no:工号','department_name:发布时部门'])" />
        </template>
      </div>
    </section>
  </div>
</template>
