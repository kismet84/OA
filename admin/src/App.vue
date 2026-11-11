<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { api, download, display, labels, setToken, type Actor, type Row } from './api';
import DataTable from './DataTable.vue';
import MessageCenter from './MessageCenter.vue';
import Calendar from './Calendar.vue';
const meetingId = ref<number | null>(null);

const me = ref<Actor | null>(null),
  development = ref(false),
  loading = ref(false),
  busy = ref(false),
  message = ref(''),
  error = ref('');
const page = ref('overview'),
  rows = ref<Row[]>([]),
  users = ref<Row[]>([]),
  departments = ref<Row[]>([]),
  types = ref<Row[]>([]);
const modal = ref(''),
  detail = ref<Row | null>(null),
  editId = ref<number | null>(null),
  offset = ref(0);
const loginForm = reactive({ login: 'admin', password: '' });
const passwordForm = reactive({ oldPassword: '', newPassword: '' });
const account = reactive({
  employeeNo: '',
  phone: '',
  jobTitle: '',
  workPhone: '',
  name: '',
  roles: ['EMPLOYEE'],
  departmentId: null as number | null,
  managerId: null as number | null,
  enabled: true,
  password: '',
});
const department = reactive({
  name: '',
  parentId: null as number | null,
  headId: null as number | null,
});
const network = reactive({ kind: 'SSID', value: '' });
const leaveType = reactive({
  code: '',
  name: '',
  deductBalance: false,
  annualLimit: null as number | null,
  proofRequired: false,
  enabled: true,
});
const balance = reactive({
  userId: null as number | null,
  typeId: null as number | null,
  year: new Date().getFullYear(),
  amount: 0,
});
const workflow = reactive({
  correctionDays: 3,
  overtimeRatio: 1,
  leaveThreshold: 1,
  travelThreshold: 1,
  expenseThreshold: 1000,
  financeId: null as number | null,
});
const application = reactive({
  userId: null as number | null,
  punchKind: 'NORMAL',
  correctionAt: '',
  kind: 'LEAVE',
  typeId: null as number | null,
  startDate: '',
  startTime: '09:00',
  endDate: '',
  endTime: '18:00',
  amount: 0,
  reason: '',
  attachmentId: null as string | null,
  items: [{ description: '', amount: 0 }],
  attachmentIds: [] as string[],
  ccUserIds: [] as number[],
});
const receipts = ref<{ id: string; name: string }[]>([]);
const paymentReason = ref('');
const deviceUser = ref<number | null>(null);
const devices = ref<Row[]>([]);
async function showDevices(id: number) {
  deviceUser.value = id;
  await run(async () => { devices.value = await api('/admin/users/' + id + '/sessions'); modal.value = 'devices'; });
}
async function kickDevice(id: string) {
  await run(async () => {
    await api('/admin/users/' + deviceUser.value + '/sessions/' + id, 'DELETE');
    devices.value = await api('/admin/users/' + deviceUser.value + '/sessions');
  }, '设备已退出');
}
const isAdmin = computed(() => me.value?.roles.split(',').includes('ADMIN'));
const attachmentName = ref(''),
  approval = reactive({ approve: true, comment: '' });
const simulator = reactive({
  userId: null as number | null,
  kind: 'NORMAL',
  latitude: 31.2304,
  longitude: 121.4737,
  accuracy: 20,
  ssid: '',
  exitIp: '127.0.0.1',
});
const filters = reactive({ userId: '', status: '', kind: '', from: '', to: '' });
const roleOptions = ['EMPLOYEE', 'SUPERVISOR', 'DEPT_HEAD', 'ADMIN', 'FINANCE'];
const nav = computed(() => isAdmin.value ? [
  { key: 'overview', title: '工作台', icon: '概' },
  { key: 'calendar', title: '日程与会议', icon: '会' },
  { key: 'departments', title: '部门组织', icon: '组' },
  { key: 'users', title: '账号与角色', icon: '人' },
  { key: 'networks', title: '公司网络', icon: '网' },
  { key: 'leave-types', title: '假期配置', icon: '假' },
  { key: 'balances', title: '假期余额', icon: '余' },
  { key: 'balance-history', title: '余额流水', icon: '账' },
  { key: 'workflow', title: '审批分流', icon: '流' },
  { key: 'requests', title: '审批单据', icon: '审' },
  { key: 'punches', title: '打卡记录', icon: '勤' },
  ...(development.value ? [{ key: 'simulator', title: '模拟打卡', icon: '测' }] : []),
  { key: 'messages', title: '消息中心', icon: '信' },
  { key: 'announcements', title: '公告管理', icon: '告' },
  { key: 'audit', title: '操作审计', icon: '志' },
] : [{ key: 'requests', title: '审批单据', icon: '审' }, { key: 'messages', title: '消息中心', icon: '信' }]);
const pageTitle = computed(() => nav.value.find((n) => n.key === page.value)?.title || '管理后台');
const enabledUsers = computed(() => users.value.filter((u) => u.enabled));
const nameOf = (id: number | null) => users.value.find((u) => u.id === id)?.name || '未指定';
const deptName = (id: number | null) =>
  departments.value.find((d) => d.id === id)?.name || '根部门';
const deptTree = computed(() => {
  const result: Row[] = [];
  function walk(parent: number | null, depth: number) {
    departments.value
      .filter((d) => d.parent_id === parent)
      .forEach((d) => {
        result.push({
          ...d,
          tree_name: '　'.repeat(depth) + (depth ? '└ ' : '') + d.name,
          head_name: nameOf(d.head_id),
        });
        walk(d.id, depth + 1);
      });
  }
  walk(null, 0);
  return result;
});
const preparedUsers = computed(() =>
  rows.value.map((u) => ({
    ...u,
    manager_name: nameOf(u.manager_id),
    role_names: u.roles
      .split(',')
      .map((r: string) => display(r))
      .join('、'),
  })),
);
const cols = (pairs: string[]) =>
  pairs.map((p) => {
    const [key, label] = p.split(':');
    return { key: key!, label: label! };
  });
const columns: Record<string, string[]> = {
  users: [
    'employee_no:工号',
    'name:姓名',
    'phone:登录手机号',
    'job_title:职务',
    'work_phone:工作电话',
    'department_name:主部门',
    'manager_name:直属上级',
    'role_names:角色',
    'enabled:启用',
    'must_change_password:需改密',
  ],
  departments: ['tree_name:部门树', 'head_name:部门负责人'],
  networks: ['kind:类型', 'value:登记值'],
  'leave-types': [
    'name:假期类型',
    'code:标识',
    'deduct_balance:扣减余额',
    'annual_limit:年度上限（天）',
    'proof_required:必须证明',
    'enabled:启用',
  ],
  'balance-history': ['user_name:员工', 'type_name:假期', 'year:年度', 'delta:变动天数', 'balance_after:变动后余额', 'request_id:关联单号', 'reason:原因', 'actor_name:操作人', 'created_at:时间'],
  balances: ['user_name:姓名', 'type_name:假期类型', 'year:年度', 'amount:剩余天数'],
  requests: [
    'id:单号',
    'user_name:申请人',
    'kind:类型',
    'type_name:假期',
    'start_at:开始时间',
    'end_at:结束时间',
    'days:天数',
    'amount:金额（元）',
    'status:状态',
    'payment_status:支付状态',
  ],
  punches: [
    'user_name:姓名',
    'punched_at:打卡时间',
    'kind:类型',
    'source:记录来源',
    'correction_request_id:补卡单号',
    'latitude:纬度',
    'longitude:经度',
    'accuracy:精度（米）',
    'ssid:Wi-Fi',
    'exit_ip:出口 IP',
    'simulated:模拟记录',
  ],
  notifications: ['message:通知内容', 'created_at:时间', 'read_at:已读时间'],
  audit: ['actor_name:操作人', 'action:操作', 'target:对象 / 变更值', 'created_at:时间'],
};
async function run(action: () => Promise<void>, success = '') {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    await action();
    if (success) message.value = success;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busy.value = false;
  }
}
async function session() {
  const data = await api('/auth/me');
  me.value = data.user;
  development.value = data.development;
  if (me.value && !me.value.mustChange) {
    await references();
    await navigate(isAdmin.value ? 'overview' : me.value.roles.split(',').includes('FINANCE') ? 'requests' : 'messages');
  }
}
async function signIn() {
  await run(async () => {
    const data = await api('/auth/login', 'POST', loginForm);
    setToken(data.token);
    loginForm.password = '';
    await session();
  });
}
async function signOut() {
  await run(async () => {
    await api('/auth/logout', 'POST');
    setToken('');
    me.value = null;
    modal.value = '';
  });
}
async function changePassword() {
  await run(async () => {
    await api('/auth/password', 'POST', passwordForm);
    setToken('');
    me.value = null;
    modal.value = '';
    passwordForm.oldPassword = '';
    passwordForm.newPassword = '';
  }, '密码已修改，请用新密码重新登录');
}
async function references() {
  if (!isAdmin.value) { users.value = await api('/colleagues'); return; }
  [users.value, departments.value, types.value] = await Promise.all([
    api('/admin/users'),
    api('/admin/departments'),
    api('/admin/leave-types'),
  ]);
}
function query(exporting = false) {
  const p = new URLSearchParams();
  if (filters.userId) p.set('userId', filters.userId);
  if (filters.kind) p.set('kind', filters.kind);
  if (page.value === 'requests' && filters.status) p.set('status', filters.status);
  if (page.value === 'punches') {
    if (filters.from) p.set('from', filters.from);
    if (filters.to) p.set('to', filters.to);
  }
  if (!exporting) p.set('offset', String(offset.value));
  return '?' + p;
}
async function load() {
  if (page.value === 'calendar') return;
  loading.value = true;
  try {
    if (page.value === 'workflow') {
      const w = await api('/admin/workflow');
      Object.assign(workflow, {
        correctionDays: w.correction_days,
        overtimeRatio: w.overtime_ratio,
        leaveThreshold: w.leave_threshold,
        travelThreshold: w.travel_threshold,
        expenseThreshold: w.expense_threshold,
        financeId: w.finance_id,
      });
    } else if (['overview', 'simulator', 'messages', 'announcements'].includes(page.value)) rows.value = [];
    else if (['requests', 'punches'].includes(page.value))
      rows.value = await api('/' + page.value + query());
    else rows.value = await api((page.value === 'notifications' ? '/' : '/admin/') + page.value);
    if (page.value === 'punches') rows.value = rows.value.map(r => ({...r, source: r.correction_request_id ? '补卡' : '现场打卡'}));
  } finally {
    loading.value = false;
  }
}
async function navigate(key: string) {
  page.value = key;
  offset.value = 0;
  Object.assign(filters, { userId: '', status: '', kind: '', from: '', to: '' });
  await load();
}
async function refresh() {
  await run(async () => {
    await references();
    await load();
  });
}
function openCreate() {
  editId.value = null;
  modal.value = page.value;
  error.value = '';
  if (page.value === 'users')
    Object.assign(account, {
      employeeNo: '',
      phone: '',
      jobTitle: '',
      workPhone: '',
      name: '',
      roles: ['EMPLOYEE'],
      departmentId: departments.value[0]?.id ?? null,
      managerId: null,
      enabled: true,
      password: '',
    });
  if (page.value === 'departments')
    Object.assign(department, { name: '', parentId: null, headId: null });
  if (page.value === 'leave-types')
    Object.assign(leaveType, {
      code: '',
      name: '',
      deductBalance: false,
      annualLimit: null,
      proofRequired: false,
      enabled: true,
    });
  if (page.value === 'requests') {
    Object.assign(application, {
      userId: null,
      punchKind: 'NORMAL',
      correctionAt: '',
      kind: 'LEAVE',
      typeId: types.value[0]?.id ?? null,
      startDate: '',
      startTime: '09:00',
      endDate: '',
      endTime: '18:00',
      reason: '',
      amount: 0,
      items: [{ description: '', amount: 0 }],
      attachmentIds: [],
      ccUserIds: [],
      attachmentId: null,
    });
    attachmentName.value = '';
    receipts.value = [];
  }
}
function edit(row: Row) {
  openCreate();
  editId.value = row.id;
  if (page.value === 'users')
    Object.assign(account, {
      employeeNo: row.employee_no,
      phone: row.phone || '',
      jobTitle: row.job_title || '',
      workPhone: row.work_phone || '',
      name: row.name,
      roles: row.roles.split(','),
      departmentId: row.department_id,
      managerId: row.manager_id,
      enabled: row.enabled,
      password: '',
    });
  if (page.value === 'departments')
    Object.assign(department, { name: row.name, parentId: row.parent_id, headId: row.head_id });
  if (page.value === 'leave-types')
    Object.assign(leaveType, {
      code: row.code,
      name: row.name,
      deductBalance: row.deduct_balance,
      annualLimit: row.annual_limit,
      proofRequired: row.proof_required,
      enabled: row.enabled,
    });
  if (page.value === 'balances')
    Object.assign(balance, {
      userId: row.user_id,
      typeId: row.type_id,
      year: row.year,
      amount: row.amount,
    });
}
async function save() {
  await run(async () => {
    const route = modal.value;
    let body: unknown;
    let method = editId.value ? 'PUT' : 'POST';
    let path = '/admin/' + route + (editId.value ? '/' + editId.value : '');
    if (route === 'users')
      body = { ...account, phone: account.phone || null, password: account.password || null };
    if (route === 'departments') body = department;
    if (route === 'networks') body = network;
    if (route === 'leave-types') body = leaveType;
    if (route === 'balances') {
      path = '/admin/balances';
      method = 'PUT';
      body = balance;
    }
    if (route === 'requests') {
      path = '/requests';
      method = 'POST';
      body = {
        ...application,
        typeId: application.kind === 'LEAVE' ? application.typeId : null,
        startAt:
          application.kind === 'CORRECTION' ? application.correctionAt : application.kind === 'EXPENSE'
            ? null
            : application.startDate + 'T' + application.startTime + ':00',
        endAt:
          ['EXPENSE', 'CORRECTION'].includes(application.kind)
            ? null
            : application.endDate + 'T' + application.endTime + ':00',
        amount: application.kind === 'EXPENSE' ? application.amount : null,
        items: application.kind === 'EXPENSE' ? application.items : null,
        attachmentIds: application.kind === 'EXPENSE' ? application.attachmentIds : null,
        attachmentId: application.kind === 'EXPENSE' ? null : application.attachmentId,
      };
    }
    await api(path, method, body);
    modal.value = '';
    await references();
    await load();
  }, '已保存');
}
async function showDetail(row: Row) {
  await run(async () => {
    detail.value = await api('/requests/' + row.id);
    paymentReason.value = '';
    approval.comment = '';
    approval.approve = true;
    modal.value = 'detail';
  });
}
async function decide() {
  await run(async () => {
    await api('/requests/' + detail.value!.id + '/decision', 'POST', approval);
    detail.value = await api('/requests/' + detail.value!.id);
    await load();
  }, '审批已处理');
}
async function cancelRequest() {
  await run(async () => {
    await api('/requests/' + detail.value!.id + '/cancel', 'POST');
    detail.value = await api('/requests/' + detail.value!.id);
    await load();
  }, '已撤回或撤销');
}
async function markPayment(status: string) {
  await run(async () => {
    await api('/requests/' + detail.value!.id + '/payment', 'POST', { status, reason: paymentReason.value });
    detail.value = await api('/requests/' + detail.value!.id);
    paymentReason.value = '';
    await load();
  }, '支付状态已更新');
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  if (!files.length) return;
  await run(async () => {
    for (const file of files) {
      const data = new FormData();
      data.append('file', file);
      const result = await api('/attachments', 'POST', data);
      if (application.kind === 'EXPENSE') {
        receipts.value.push(result);
        application.attachmentIds.push(result.id);
      } else {
        application.attachmentId = result.id;
        attachmentName.value = result.name;
      }
    }
  }, '附件已上传');
  input.value = '';
}

async function exportCsv() {
  await run(async () => {
    await download(
      '/admin/exports/' + page.value + '.csv' + query(true),
      page.value === 'requests' ? '审批单.csv' : '打卡记录.csv',
    );
  }, '已导出');
}
async function simulate() {
  await run(async () => {
    const { userId, exitIp, ...punch } = simulator;
    const r = await api('/admin/dev/punch', 'POST', { userId, exitIp, punch });
    message.value = '打卡成功，记录编号 #' + r.id;
  });
}
onMounted(async () => {
  window.addEventListener('oa-session-expired', () => {
    me.value = null;
    modal.value = '';
  });
  if (sessionStorage.getItem('oa-token')) await run(session);
});
</script>

<template>
  <div v-if="!me" class="login-layout">
    <section class="login-intro">
      <div class="brand-mark">OA</div>
      <p class="eyebrow">公司内部管理平台</p>
      <h1>组织有序<br />协作有据</h1>
      <p>账号与组织 · 申请与审批 · 出勤记录</p>
      <small>一期管理后台 / 员工消息中心与授权管理入口</small>
    </section>
    <section class="login-panel">
      <form @submit.prevent="signIn">
        <p class="eyebrow">欢迎回来</p>
        <h2>管理员登录</h2>
        <p class="muted">使用工号或手机号登录管理后台。</p>
        <label
          >工号 / 手机号<input v-model="loginForm.login" autocomplete="username" required /></label
        ><label
          >密码<input
            v-model="loginForm.password"
            type="password"
            autocomplete="current-password"
            required
        /></label>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <p v-if="message" role="status" class="success">{{ message }}</p>
        <button class="primary wide" :disabled="busy">
          {{ busy ? '正在登录…' : '登录管理后台' }}
        </button>
      </form>
    </section>
  </div>
  <div v-else-if="me.mustChange" class="first-password">
    <form class="card" @submit.prevent="changePassword">
      <div class="brand-mark">OA</div>
      <h2>首次登录，请修改密码</h2>
      <p class="muted">设置新密码后重新登录，才能访问管理功能。</p>
      <label
        >原密码<input
          v-model="passwordForm.oldPassword"
          type="password"
          autocomplete="current-password"
          required /></label
      ><label
        >新密码（10–72 位）<input
          v-model="passwordForm.newPassword"
          type="password"
          minlength="10"
          maxlength="72"
          autocomplete="new-password"
          required
      /></label>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <button class="primary" :disabled="busy">保存新密码</button
      ><button type="button" @click="signOut">退出登录</button>
    </form>
  </div>
  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">OA</span>
        <div>公司 OA<small>管理后台</small></div>
      </div>
      <p class="nav-caption">管理工作区</p>
      <nav>
        <button
          v-for="n in nav"
          :key="n.key"
          :class="{ active: page === n.key }"
          :disabled="busy"
          @click="run(() => navigate(n.key))"
        >
          <span class="nav-icon">{{ n.icon }}</span
          >{{ n.title }}
        </button>
      </nav>
      <div class="sidebar-foot">
        <span class="dot" />{{ development ? '本机开发环境' : '正式环境'
        }}<small>业务时区：亚洲 / 上海</small>
      </div>
    </aside>
    <main>
      <header>
        <div>
          <span class="eyebrow">管理工作区 / {{ pageTitle }}</span>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="user-bar">
          <span>{{ me.name }}</span
          ><button
            @click="
              modal = 'password';
              error = '';
            "
          >
            修改密码</button
          ><button @click="signOut">退出</button>
        </div>
      </header>
      <div v-if="error" class="notice error" role="alert">
        {{ error }}<button @click="error = ''" aria-label="关闭错误">×</button>
      </div>
      <div v-if="message" class="notice success" role="status">
        {{ message }}<button @click="message = ''" aria-label="关闭提示">×</button>
      </div>
      <section v-if="page === 'overview'" class="overview">
        <div class="welcome card">
          <p class="eyebrow">组织与出勤</p>
          <h2>把日常管理，放在一个工作区。</h2>
          <p>维护组织和审批规则，核对申请与打卡记录。所有管理操作均留有审计记录。</p>
          <button class="primary" @click="run(() => navigate('requests'))">查看审批单据 →</button>
        </div>
        <div class="stats">
          <div class="card">
            <span>在册账号</span><strong>{{ users.length }}</strong
            ><small>有效账号 {{ enabledUsers.length }} 人</small>
          </div>
          <div class="card">
            <span>组织部门</span><strong>{{ departments.length }}</strong
            ><small>每人归属一个主部门</small>
          </div>
          <div class="card">
            <span>假期类型</span><strong>{{ types.filter((t) => t.enabled).length }}</strong
            ><small>支持半天与按年额度</small>
          </div>
        </div>
        <div class="card">
          <h3>开始使用</h3>
          <div class="steps">
            <div>
              <b>01 · 建立组织</b>
              <p>维护部门树、负责人、直属上级，再开通员工账号。</p>
            </div>
            <div>
              <b>02 · 配置规则</b>
              <p>登记公司网络，录入假期余额与审批分流阈值。</p>
            </div>
            <div>
              <b>03 · 核对业务</b>
              <p>提交测试申请，处理审批，再核对打卡记录。</p>
            </div>
          </div>
        </div>
      </section>
      <section v-else-if="page === 'workflow'" class="card form-card">
        <h2>按额度与天数自动分流</h2>
        <p class="muted">
          阈值以内直属上级审批一次；超过阈值增加部门负责人或财务/行政。直属上级已是部门负责人，或未指定部门负责人，天数类申请只批一次。配置仅影响新申请。
        </p>
        <form
          @submit.prevent="
            run(async () => {
              await api('/admin/workflow', 'PUT', workflow);
            }, '审批分流已保存')
          "
        >
          <label
            >请假分流阈值（天）<input
              v-model.number="workflow.leaveThreshold"
              type="number"
              min="0.5"
              step="0.5"
              required /></label
          ><label
            >出差 / 外出分流阈值（天）<input
              v-model.number="workflow.travelThreshold"
              type="number"
              min="0.5"
              step="0.5"
              required /></label
          ><label
            >报销分流阈值（元）<input
              v-model.number="workflow.expenseThreshold"
              type="number"
              min="0"
              step="0.01"
              required /></label
          ><label
            >财务 / 行政二级审批人<select v-model="workflow.financeId">
              <option :value="null">未指定</option>
              <option
                v-for="u in enabledUsers.filter((u) => u.roles.split(',').includes('FINANCE'))"
                :key="u.id"
                :value="u.id"
              >
                {{ u.name }}
              </option>
            </select></label
          ><label>补卡期限（补卡日期结束后自然日数）<input v-model.number="workflow.correctionDays" type="number" min="0" max="365" step="1" required /></label>
          <label>加班兑换调休比例<input v-model.number="workflow.overtimeRatio" type="number" min="0.01" max="100" step="0.01" required /></label>
          <p class="muted">加班沿用请假分流阈值，按半天计天数；兑换比例在提交时保存。补卡只审批一次，已批准不可撤销。已使用的加班额度须先恢复余额才能撤销。</p>
          <button class="primary" :disabled="busy">保存分流配置</button>
        </form>
      </section>
      <section v-else-if="page === 'simulator'" class="card form-card">
        <span class="badge pending">仅开发环境</span>
        <h2>模拟打卡</h2>
        <p class="muted">
          手动输入设备定位和网络。出勤仍须在批准的出差或外出时段内；模拟记录会单独标记并留审计。
        </p>
        <form @submit.prevent="simulate">
          <label
            >模拟员工<select v-model="simulator.userId" required>
              <option :value="null" disabled>请选择员工</option>
              <option v-for="u in enabledUsers" :key="u.id" :value="u.id">
                {{ u.name }}（{{ u.employee_no }}）
              </option>
            </select></label
          ><label
            >打卡类型<select v-model="simulator.kind">
              <option value="NORMAL">正常打卡</option>
              <option value="FIELD">出勤</option>
            </select></label
          >
          <div class="form-grid">
            <label
              >纬度<input
                v-model.number="simulator.latitude"
                type="number"
                min="-90"
                max="90"
                step="any"
                required /></label
            ><label
              >经度<input
                v-model.number="simulator.longitude"
                type="number"
                min="-180"
                max="180"
                step="any"
                required
            /></label>
          </div>
          <label
            >定位精度（米）<input
              v-model.number="simulator.accuracy"
              type="number"
              min="0.01"
              max="100000"
              step="any"
              required /></label
          ><label>公司 Wi-Fi 名称<input v-model="simulator.ssid" maxlength="200" /></label
          ><label>办公网出口 IP<input v-model="simulator.exitIp" required /></label
          ><button class="primary" :disabled="busy">提交模拟打卡</button>
        </form>
      </section>
      <Calendar v-else-if="page==='calendar' && me" :me="me" :management="isAdmin" :meeting-id="meetingId" />
      <MessageCenter @open-meeting="id=>{meetingId=id;page='calendar'}" v-else-if="['messages','announcements'].includes(page)" :key="page" :management="page==='announcements'" :departments="departments" @open-request="showDetail" />
      <section v-else class="card records">
        <div class="section-toolbar">
          <div>
            <h2>{{ pageTitle }}</h2>
            <p class="muted">
              {{
                page === 'balances'
                  ? '录入当前剩余额度；审批通过后自动扣减，撤销后返还。'
                  : page === 'punches'
                    ? '仅记录打卡事实，不计算迟到、早退或缺卡。'
                    : page === 'audit'
                      ? '最近 500 条管理操作，不记录密码或会话令牌。'
                      : page === 'notifications'
                        ? '最新 200 条站内通知。'
                        : '维护与核对公司日常管理数据。'
              }}
            </p>
          </div>
          <div class="actions">
            <button :disabled="loading || busy" @click="refresh">刷新</button
            ><button
              v-if="isAdmin && ['requests', 'punches'].includes(page)"
              @click="exportCsv"
              :disabled="busy"
            >
              导出 CSV</button
            ><button
              v-if="isAdmin && !['punches', 'audit', 'notifications', 'balance-history'].includes(page)"
              class="primary"
              @click="openCreate"
            >
              {{ page === 'requests' ? '提交申请' : page === 'balances' ? '录入余额' : '新增' }}
            </button>
          </div>
        </div>
        <form
          v-if="['requests', 'punches'].includes(page)"
          class="filters"
          @submit.prevent="
            run(async () => {
              offset = 0;
              await load();
            })
          "
        >
          <label
            >员工<select v-model="filters.userId">
              <option value="">全部员工</option>
              <option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }}</option>
            </select></label
          ><label
            >类型<select v-model="filters.kind">
              <option value="">全部类型</option>
              <option
                v-for="k in page === 'requests'
                  ? ['LEAVE', 'TRAVEL', 'OUTING', 'EXPENSE', 'OVERTIME', 'CORRECTION']
                  : ['NORMAL', 'FIELD']"
                :key="k"
                :value="k"
              >
                {{ display(k) }}
              </option>
            </select></label
          ><label v-if="page === 'requests'"
            >状态<select v-model="filters.status">
              <option value="">全部状态</option>
              <option
                v-for="s in ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED']"
                :key="s"
                :value="s"
              >
                {{ display(s) }}
              </option>
            </select></label
          ><template v-else
            ><label>开始日期<input v-model="filters.from" type="date" /></label
            ><label>结束日期<input v-model="filters.to" type="date" /></label></template
          ><button :disabled="busy">查询</button>
        </form>
        <p v-if="loading" class="muted">正在加载…</p>
        <DataTable
          v-else
          :columns="cols(columns[page] || [])"
          :rows="page === 'users' ? preparedUsers : page === 'departments' ? deptTree : rows"
        >
          <template
            v-if="
              [
                'users',
                'departments',
                'leave-types',
                'balances',
                'networks',
                'requests',
                'notifications',
              ].includes(page)
            "
            #actions="{ row }"
          >
            <button
              v-if="['users', 'departments', 'leave-types', 'balances'].includes(page)"
              @click="edit(row)"
            >
              编辑
            </button>
            <button
              v-if="page === 'networks'"
              class="danger"
              @click="
                run(async () => {
                  await api('/admin/networks/' + row.id, 'DELETE');
                  await load();
                }, '网络登记已移除')
              "
            >
              移除
            </button>
            <button v-if="page === 'users'" @click="showDevices(row.id)">登录设备</button>
            <button v-if="page === 'requests'" @click="showDetail(row)">查看 / 审批</button>
            <button
              v-if="page === 'notifications' && !row.read_at"
              @click="
                run(async () => {
                  await api('/notifications/' + row.id + '/read', 'POST');
                  await load();
                })
              "
            >
              标为已读
            </button>
          </template>
        </DataTable>
        <div v-if="['requests', 'punches'].includes(page)" class="pagination">
          <span>第 {{ Math.floor(offset / 200) + 1 }} 页 · 每页最多 200 条</span
          ><button
            :disabled="offset === 0 || busy"
            @click="
              run(async () => {
                offset = Math.max(0, offset - 200);
                await load();
              })
            "
          >
            上一页</button
          ><button
            :disabled="rows.length < 200 || busy"
            @click="
              run(async () => {
                offset += 200;
                await load();
              })
            "
          >
            下一页
          </button>
        </div>
      </section>
      <footer>公司 OA · 一期管理后台<span>数据按权限访问 · 管理操作留痕</span></footer>
    </main>
    <div v-if="modal" class="modal-backdrop" @click.self="modal = ''">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        :aria-label="modal === 'devices' ? '登录设备' : modal === 'detail' ? '审批单详情' : '编辑表单'"
      >
        <div class="modal-header">
          <h2>
            {{
              modal === 'devices'
                ? '登录设备'
                : modal === 'password'
                ? '修改密码'
                : modal === 'detail'
                  ? '审批单 #' + detail?.id
                  : (editId ? '编辑' : '新增') + pageTitle
            }}
          </h2>
          <button @click="modal = ''" aria-label="关闭弹窗">×</button>
        </div>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <div v-if="modal === 'devices'">
          <h3>当前有效登录设备</h3>
          <p v-if="!devices.length">暂无有效设备</p>
          <div v-for="d in devices" :key="d.id">
            <p>{{ d.device_name }} · {{ d.platform }}{{ d.current ? '（当前设备）' : '' }}</p>
            <p>登录：{{ d.created_at }}；到期：{{ d.expires_at }}</p>
            <button class="danger" :disabled="busy" @click="kickDevice(d.id)">退出此设备</button>
          </div>
        </div>
        <form v-else-if="modal === 'password'" @submit.prevent="changePassword">
          <label
            >原密码<input
              v-model="passwordForm.oldPassword"
              type="password"
              autocomplete="current-password"
              required /></label
          ><label
            >新密码（10–72 位）<input
              v-model="passwordForm.newPassword"
              type="password"
              autocomplete="new-password"
              minlength="10"
              maxlength="72"
              required /></label
          ><button class="primary" :disabled="busy">保存并重新登录</button>
        </form>
        <div v-else-if="modal === 'detail' && detail">
          <dl class="detail-grid">
            <div>
              <dt>申请人</dt>
              <dd>{{ detail.user_name || nameOf(detail.user_id) }}</dd>
            </div>
            <div>
              <dt>申请类型 / 状态</dt>
              <dd>{{ display(detail.kind) }} / {{ display(detail.status) }}</dd>
            </div>
            <div>
              <dt>开始时间</dt>
              <dd>{{ display(detail.start_at) }}</dd>
            </div>
            <div>
              <dt>结束时间</dt>
              <dd>{{ display(detail.end_at) }}</dd>
            </div>
            <div>
              <dt>天数 / 金额</dt>
              <dd>{{ display(detail.days) }} 天 / {{ display(detail.amount) }} 元</dd>
            </div>
            <div>
              <dt>申请理由</dt>
              <dd>{{ detail.reason }}</dd>
            </div>
          </dl>
          <button
            v-if="detail.attachment_id"
            @click="
              run(async () => {
                await download('/attachments/' + detail!.attachment_id, '证明文件');
              })
            "
          >
            下载证明文件
          </button>
          <template v-if="detail.kind === 'EXPENSE'">
            <h3>费用明细</h3>
            <DataTable :rows="detail.items" :columns="cols(['description:用途 / 类别', 'amount:金额（元）'])" />
            <h3>票据附件</h3>
            <p v-if="!detail.attachments.length" class="muted">历史单据未上传票据</p>
            <button v-for="file in detail.attachments" :key="file.id" @click="run(() => download('/attachments/' + file.id, file.original_name))">下载票据：{{ file.original_name }}</button>
            <h3>支付状态：{{ display(detail.payment_status) }}</h3>
            <p>最近操作时间：{{ display(detail.payment_acted_at) }}</p>
            <ol class="timeline"><li v-for="h in detail.payment_history" :key="h.id">{{ h.actor_name }} · {{ display(h.status) }} · {{ display(h.acted_at) }}<p>{{ h.reason }}</p></li></ol>
            <form v-if="detail.status === 'APPROVED' && (isAdmin || me.roles.split(',').includes('FINANCE'))" @submit.prevent="markPayment(detail!.payment_status === 'PAID' ? 'UNPAID' : 'PAID')">
              <label>支付更正原因（改回待支付必填）<textarea v-model="paymentReason" maxlength="1000" :required="detail.payment_status === 'PAID'" /></label>
              <button class="primary" :disabled="busy">{{ detail.payment_status === 'PAID' ? '改回待支付' : '标记已支付' }}</button>
            </form>
          </template>
          <p v-if="detail.kind === 'CORRECTION'">补卡类型：{{ display(detail.punch_kind) }}；批准后记录为补卡，不含现场定位。</p>
          <p v-if="detail.kind === 'OVERTIME'">兑换比例：{{ detail.overtime_ratio }}；最终批准计入 {{ detail.credited_days }} 天调休（开始年度）。</p>
          <h3>抄送名单</h3><p>{{ detail.cc?.map((u: Row) => u.name + '（' + u.employee_no + '）').join('、') || '无' }}</p>
          <h3>审批记录</h3>
          <ol class="timeline">
            <li v-for="s in detail.steps" :key="s.step">
              <b>{{ s.approver_name }} · {{ display(s.status) }}</b>
              <p>
                {{ s.comment || '暂无意见' }} <small>{{ s.acted_at }}</small>
              </p>
            </li>
          </ol>
          <form
            v-if="detail.status === 'PENDING' && detail.user_id !== me.id && (isAdmin || detail.steps.some((s: Row) => s.step === detail!.current_step && s.approver_id === me!.id))"
            @submit.prevent="decide"
          >
            <label
              >审批结果<select v-model="approval.approve">
                <option :value="true">通过</option>
                <option :value="false">驳回</option>
              </select></label
            ><label
              >审批意见（驳回必填）<textarea
                v-model="approval.comment"
                :required="!approval.approve"
                maxlength="1000"
              /></label
            ><button class="primary" :disabled="busy">提交审批</button>
          </form>
          <div v-if="isAdmin && (detail.status === 'PENDING' || (detail.status === 'APPROVED' && !['EXPENSE','CORRECTION'].includes(detail.kind) && new Date(detail.start_at).getTime() > Date.now()))" class="cancel-area">
            <p class="muted">
              待审批可撤回；已批准的申请仅在尚未开始时可撤销。已批准的报销和补卡不可撤销；加班撤销退回已兑换调休。
            </p>
            <button class="danger" :disabled="busy" @click="cancelRequest">
              {{ detail.status === 'PENDING' ? '撤回申请' : '撤销申请' }}
            </button>
          </div>
        </div>
        <form v-else @submit.prevent="save">
          <template v-if="modal === 'users'"
            ><label
              >工号<input
                v-model="account.employeeNo"
                pattern="[A-Za-z0-9_-]{1,40}"
                required /></label
            ><label>姓名<input v-model="account.name" maxlength="80" required /></label
            ><label>职务<input v-model="account.jobTitle" maxlength="100" /></label
            ><label>工作电话<input v-model="account.workPhone" maxlength="40" pattern="[0-9+() -]{0,40}" /></label
            ><label>登录手机号<input v-model="account.phone" type="tel" maxlength="20" /></label
            ><label
              >主部门<select v-model="account.departmentId" required>
                <option v-for="d in deptTree" :key="d.id" :value="d.id">{{ d.tree_name }}</option>
              </select></label
            ><label
              >直属上级<select v-model="account.managerId">
                <option :value="null">未指定（由部门负责人审批）</option>
                <option
                  v-for="u in enabledUsers.filter(
                    (u) =>
                      u.id !== editId &&
                      (u.roles.includes('SUPERVISOR') || u.roles.includes('DEPT_HEAD')),
                  )"
                  :key="u.id"
                  :value="u.id"
                >
                  {{ u.name }}
                </option>
              </select></label
            >
            <fieldset>
              <legend>角色（可多选）</legend>
              <label v-for="r in roleOptions" :key="r" class="check"
                ><input v-model="account.roles" type="checkbox" :value="r" />{{ labels[r] }}</label
              >
            </fieldset>
            <label
              >{{
                editId
                  ? '重置密码（留空则保留；重置后须首次改密）'
                  : '初始密码（10–72 位，首次登录须改密）'
              }}<input
                v-model="account.password"
                type="password"
                autocomplete="new-password"
                minlength="10"
                maxlength="72"
                :required="!editId" /></label
            ><label class="check"
              ><input v-model="account.enabled" type="checkbox" />启用账号</label
            >
            <p class="muted">
              停用将立即使已有会话失效，并保留历史审批和打卡。若有下属、负责部门或待办审批，请先调整。
            </p></template
          >
          <template v-if="modal === 'departments'"
            ><label>部门名称<input v-model="department.name" maxlength="100" required /></label
            ><label
              >上级部门<select v-model="department.parentId">
                <option :value="null">根部门</option>
                <option
                  v-for="d in deptTree.filter((d) => d.id !== editId)"
                  :key="d.id"
                  :value="d.id"
                >
                  {{ d.tree_name }}
                </option>
              </select></label
            ><label
              >部门负责人<select v-model="department.headId">
                <option :value="null">未指定</option>
                <option
                  v-for="u in enabledUsers.filter((u) => u.roles.includes('DEPT_HEAD'))"
                  :key="u.id"
                  :value="u.id"
                >
                  {{ u.name }}
                </option>
              </select></label
            ></template
          >
          <template v-if="modal === 'networks'"
            ><label
              >网络类型<select v-model="network.kind">
                <option value="SSID">公司 Wi-Fi（SSID）</option>
                <option value="IP">办公网出口 IP</option>
              </select></label
            ><label>登记值<input v-model="network.value" maxlength="200" required /></label
          ></template>
          <template v-if="modal === 'leave-types'"
            ><label
              >英文标识<input v-model="leaveType.code" pattern="[A-Z_]{2,30}" required /></label
            ><label>假期名称<input v-model="leaveType.name" maxlength="60" required /></label
            ><label
              >年度可休天数上限（留空为不限）<input
                v-model.number="leaveType.annualLimit"
                type="number"
                min="0.5"
                step="0.5"
                @change="
                  leaveType.annualLimit =
                    leaveType.annualLimit === ('' as any) ? null : leaveType.annualLimit
                " /></label
            ><label class="check"
              ><input
                v-model="leaveType.deductBalance"
                type="checkbox"
              />批准后扣减手工录入的余额</label
            ><label class="check"
              ><input v-model="leaveType.proofRequired" type="checkbox" />必须上传证明</label
            ><label class="check"
              ><input v-model="leaveType.enabled" type="checkbox" />启用假期类型</label
            >
            <p class="muted">
              法定假请按公司所在地规定配置年度可休天数。病假建议附证明；配置仅影响新申请。
            </p></template
          >
          <template v-if="modal === 'balances'"
            ><label
              >员工<select v-model="balance.userId" required>
                <option :value="null" disabled>请选择</option>
                <option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }}</option>
              </select></label
            ><label
              >假期类型<select v-model="balance.typeId" required>
                <option :value="null" disabled>请选择</option>
                <option
                  v-for="t in types.filter((t) => t.deduct_balance)"
                  :key="t.id"
                  :value="t.id"
                >
                  {{ t.name }}
                </option>
              </select></label
            ><label
              >年度<input
                v-model.number="balance.year"
                type="number"
                min="2020"
                max="2100"
                required /></label
            ><label
              >当前剩余天数<input
                v-model.number="balance.amount"
                type="number"
                min="0"
                max="9999"
                step="0.0001"
                required /></label
          ></template>
          <template v-if="modal === 'requests'"
            ><label
              >代提交员工<select v-model="application.userId" required>
                <option :value="null" disabled>请选择</option>
                <option v-for="u in enabledUsers" :key="u.id" :value="u.id">
                  {{ u.name }}（{{ u.employee_no }}）
                </option>
              </select></label
            ><label
              >申请类型<select v-model="application.kind" aria-label="申请类型">
                <option value="LEAVE">请假</option>
                <option value="TRAVEL">出差</option>
                <option value="OUTING">外出</option>
                <option value="EXPENSE">报销</option>
                <option value="OVERTIME">加班</option>
                <option value="CORRECTION">补卡</option>
              </select></label
            ><label v-if="application.kind === 'LEAVE'"
              >假期类型<select v-model="application.typeId" required>
                <option v-for="t in types.filter((t) => t.enabled)" :key="t.id" :value="t.id">
                  {{ t.name
                  }}{{
                    t.proof_required ? '（必须证明）' : t.code === 'SICK' ? '（建议证明）' : ''
                  }}
                </option>
              </select></label
            ><template v-if="application.kind === 'CORRECTION'">
              <label>补卡类型<select v-model="application.punchKind"><option value="NORMAL">正常打卡</option><option value="FIELD">出勤</option></select></label>
              <label>补卡时间（北京时间）<input v-model="application.correctionAt" type="datetime-local" required /></label>
              <p class="muted">仅可补交过去时刻；出勤须在本人有效的已批准出差或外出时段内。不要求当前定位和历史网络。</p>
            </template><template v-else-if="application.kind !== 'EXPENSE'"
              ><div class="form-grid">
                <label>开始日期<input v-model="application.startDate" type="date" required /></label
                ><label
                  >开始时间<select v-model="application.startTime">
                    <option>09:00</option>
                    <option>13:00</option>
                  </select></label
                ><label>结束日期<input v-model="application.endDate" type="date" required /></label
                ><label
                  >结束时间<select v-model="application.endTime">
                    <option>13:00</option>
                    <option>18:00</option>
                  </select></label
                >
              </div>
              <p class="muted">
                按自然日计算，含周末；上午、下午各半天。请假、出差、外出跨年请分开申请；加班计入开始年度调休。
              </p></template
            ><template v-else><label
              >报销金额（元）<input
                v-model.number="application.amount"
                type="number"
                min="0.01"
                step="0.01"
                required /></label>
              <h3>费用明细（至少一行）</h3>
              <div v-for="(item, index) in application.items" :key="index" class="form-grid">
                <label>用途 / 类别<input v-model="item.description" maxlength="200" required /></label>
                <label>明细金额（元）<input v-model.number="item.amount" type="number" min="0.01" step="0.01" required /></label>
                <button type="button" :disabled="application.items.length === 1" @click="application.items.splice(index, 1)">删除明细</button>
              </div>
              <button type="button" @click="application.items.push({ description: '', amount: 0 })">添加明细</button>
              <p>明细合计：{{ application.items.reduce((sum, item) => sum + Number(item.amount), 0).toFixed(2) }} 元，须与申报总额一致。</p>
            </template><label>抄送同事（可多选）
              <select v-model="application.ccUserIds" multiple aria-label="抄送同事（可多选）">
                <option v-for="u in enabledUsers.filter(u => u.id !== application.userId)" :key="u.id" :value="u.id">{{ u.name }}（{{ u.employee_no }}）</option>
              </select>
            </label><label
              >申请理由<textarea v-model="application.reason" maxlength="2000" required /></label
            ><label
              >{{ application.kind === 'EXPENSE' ? '票据附件（必填，可多张）' : '证明文件（PDF / PNG / JPEG，最多 10MB）' }}<input
                type="file"
                :multiple="application.kind === 'EXPENSE'"
                accept=".pdf,.png,.jpg,.jpeg"
                @change="upload"
            /></label>
            <template v-if="application.kind === 'EXPENSE'">
              <p class="muted">每张不超过 10MB，支持 PDF / PNG / JPEG，最多 30 张。至少上传一张。</p>
              <p v-for="(file, index) in receipts" :key="file.id">{{ file.name }} <button type="button" @click="receipts.splice(index, 1); application.attachmentIds.splice(index, 1)">移除票据</button></p>
            </template><p v-else class="muted">{{ attachmentName || '尚未上传证明' }}</p></template
          >
          <div class="modal-footer">
            <button type="button" @click="modal = ''">取消</button
            ><button class="primary" :disabled="busy">{{ busy ? '正在保存…' : '保存' }}</button>
          </div>
        </form>
      </section>
    </div>
  </div>
</template>
