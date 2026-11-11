<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { api, display, type Actor, type Row } from './api';
import DataTable from './DataTable.vue';
const props = defineProps<{ me: Actor; management?: boolean; meetingId?: number | null }>();
const today = () =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
const from = ref(today()),
  to = ref(today()),
  mine = ref(!props.management),
  roomFilter = ref(''),
  organizerFilter = ref(''),
  cancelled = ref(false),
  offset = ref(0);
const rooms = ref<Row[]>([]),
  people = ref<Row[]>([]),
  rows = ref<Row[]>([]),
  occupancy = ref<Row[]>([]),
  detail = ref<Row | null>(null),
  editing = ref(false),
  roomEditing = ref(false);
const busy = ref(false),
  error = ref(''),
  message = ref(''),
  warnings = ref<string[]>([]),
  reason = ref('');
const form = reactive({
  id: null as number | null,
  title: '',
  startAt: '',
  endAt: '',
  roomId: null as number | null,
  description: '',
  attendeeIds: [] as number[],
});
const room = reactive({
  id: null as number | null,
  name: '',
  location: '',
  capacity: null as number | null,
  enabled: true,
});
const cols = (pairs: string[]) =>
  pairs.map((p) => {
    const [key, label] = p.split(':');
    return { key: key!, label: label! };
  });
const responses: Record<string, string> = { PENDING: '待回复', ACCEPTED: '接受', DECLINED: '请假' };
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await action();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busy.value = false;
  }
}
async function references() {
  // Room maintenance and employee selectors load every page, not just the first 200 entries.
  const all: Row[] = [];
  for (let offset = 0; ; offset += 200) {
    const part = await api<Row[]>(
      '/meeting-rooms?management=' + !!props.management + '&offset=' + offset,
    );
    all.push(...part);
    if (part.length < 200) break;
  }
  rooms.value = all;
  people.value = [{ ...props.me, employee_no: '本人' }, ...(await api<Row[]>('/colleagues'))];
}
function nextDay(day: string) {
  return new Date(Date.parse(day + 'T00:00:00Z') + 86400000).toISOString().slice(0, 10);
}
function params() {
  const q = new URLSearchParams({
    from: from.value + 'T00:00:00',
    to: nextDay(to.value) + 'T00:00:00',
    mine: String(mine.value),
    includeCancelled: String(cancelled.value),
    offset: String(offset.value),
  });
  if (roomFilter.value) q.set('roomId', roomFilter.value);
  if (organizerFilter.value) q.set('organizerId', organizerFilter.value);
  return q;
}
async function load() {
  rows.value = await api('/meetings?' + params());
}
async function open(id: number) {
  detail.value = await api('/meetings/' + id);
  editing.value = false;
  reason.value = '';
}
function newMeeting() {
  detail.value = null;
  warnings.value = [];
  message.value = '';
  Object.assign(form, {
    id: null,
    title: '',
    startAt: from.value + 'T09:00',
    endAt: from.value + 'T10:00',
    roomId: null,
    description: '',
    attendeeIds: [],
  });
  editing.value = true;
  occupancy.value = [];
}
function edit() {
  const d = detail.value!;
  if (d.attendees.some((p: Row) => !people.value.some((u) => u.id === p.user_id)))
    warnings.value = ['已停用参会人将在保存时移出名单'];
  Object.assign(form, {
    id: d.id,
    title: d.title,
    startAt: d.start_at,
    endAt: d.end_at,
    roomId: d.room_id,
    description: d.description || '',
    attendeeIds: d.attendees
      .filter((p: Row) => people.value.some((u) => u.id === p.user_id))
      .map((p: Row) => p.user_id),
  });
  editing.value = true;
  void run(loadOccupancy);
}
async function loadOccupancy() {
  occupancy.value = [];
  if (!form.roomId || !form.startAt || !form.endAt) return;
  const day = form.startAt.slice(0, 10),
    endDay = form.endAt.slice(0, 10);
  const q = new URLSearchParams({
    from: day + 'T00:00:00',
    to: nextDay(endDay) + 'T00:00:00',
    roomId: String(form.roomId),
  });
  for (let offset = 0; ; offset += 200) {
    q.set('offset', String(offset));
    const part = await api<Row[]>('/meetings?' + q);
    occupancy.value.push(...part);
    if (part.length < 200) break;
  }
}
async function save() {
  await run(async () => {
    const result = await api(
      form.id ? '/meetings/' + form.id : '/meetings',
      form.id ? 'PUT' : 'POST',
      form,
    );
    warnings.value = result.warnings;
    message.value = '会议已保存';
    editing.value = false;
    await open(result.id);
    await load();
  });
}
async function cancel() {
  await run(async () => {
    await api('/meetings/' + detail.value!.id + '/cancel', 'POST');
    await open(detail.value!.id);
    await load();
  });
}
async function reply(response: string) {
  await run(async () => {
    await api('/meetings/' + detail.value!.id + '/reply', 'POST', {
      response,
      reason: reason.value,
    });
    await open(detail.value!.id);
    message.value = '参会回复已保存';
  });
}
function editRoom(r?: Row) {
  Object.assign(
    room,
    r ? { ...r } : { id: null, name: '', location: '', capacity: null, enabled: true },
  );
  roomEditing.value = true;
}
onMounted(() =>
  run(async () => {
    await references();
    await load();
    if (props.meetingId) await open(props.meetingId);
  }),
);
watch(
  () => props.meetingId,
  (id) => {
    if (id) void run(() => open(id));
  },
);
</script>
<template>
  <section class="card">
    <p>日程与会议 · 北京时间（Asia/Shanghai）· 会议室即时预定，无需审批</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status" class="success">{{ message }}</p>
    <p v-for="w in warnings" :key="w" role="status">提示：{{ w }}</p>
    <form
      class="filters"
      @submit.prevent="
        run(async () => {
          offset = 0;
          await load();
        })
      "
    >
      <label>起始日期<input v-model="from" type="date" required /></label
      ><label>截止日期<input v-model="to" type="date" required /></label>
      <label
        >会议室筛选<select v-model="roomFilter">
          <option value="">全部会议室</option>
          <option v-for="r in rooms" :key="r.id" :value="r.id">
            {{ r.name }}{{ r.enabled ? '' : '（已停用）' }}
          </option>
        </select></label
      >
      <label v-if="management"
        >组织者筛选<select v-model="organizerFilter">
          <option value="">全部组织者</option>
          <option v-for="p in people" :key="p.id" :value="p.id">
            {{ p.name }} · {{ p.employee_no }}
          </option>
        </select></label
      >
      <label><input v-model="mine" type="checkbox" />只看我的日程</label
      ><label><input v-model="cancelled" type="checkbox" />含已取消</label
      ><button :disabled="busy">查询会议</button>
    </form>
    <button class="primary" :disabled="busy" @click="newMeeting">创建会议 / 预定会议室</button>
    <DataTable
      :rows="rows"
      :columns="
        cols([
          'title:标题',
          'start_at:开始时间',
          'end_at:结束时间',
          'room_name:会议室',
          'organizer_name:组织者',
          'cancelled_at:取消时间',
        ])
      "
      ><template #actions="{ row }"
        ><button :disabled="busy" @click="run(() => open(row.id))">查看会议</button></template
      ></DataTable
    >
    <div class="pagination">
      <span>第 {{ offset / 200 + 1 }} 页 · 每页最多200条</span
      ><button
        :disabled="busy || !offset"
        @click="
          run(async () => {
            offset -= 200;
            await load();
          })
        "
      >
        上一页</button
      ><button
        :disabled="busy || rows.length < 200"
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
  <section v-if="management" class="card">
    <h2>会议室维护</h2>
    <button :disabled="busy" @click="editRoom()">新增会议室</button
    ><DataTable
      :rows="rooms"
      :columns="cols(['name:名称', 'location:地点', 'capacity:容纳人数', 'enabled:启用'])"
      ><template #actions="{ row }"
        ><button :disabled="busy" @click="editRoom(row)">修改会议室</button
        ><button
          :disabled="busy"
          @click="
            run(async () => {
              message = (await api('/meeting-rooms/' + row.id, 'DELETE')).message;
              await references();
            })
          "
        >
          删除 / 停用
        </button></template
      ></DataTable
    >
  </section>
  <div v-if="editing || detail || roomEditing" class="modal-backdrop">
    <section
      class="modal"
      role="dialog"
      aria-modal="true"
      :aria-label="roomEditing ? '会议室维护' : editing ? '编辑会议' : '会议详情'"
    >
      <div class="modal-header">
        <h2>{{ roomEditing ? '会议室维护' : editing ? '编辑会议' : detail?.title }}</h2>
        <button
          aria-label="关闭会议弹窗"
          :disabled="busy"
          @click="
            editing = false;
            detail = null;
            roomEditing = false;
          "
        >
          ×
        </button>
      </div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="message" class="success" role="status">{{ message }}</p>
      <p v-for="w in warnings" :key="w">提示：{{ w }}</p>
      <form
        v-if="roomEditing"
        @submit.prevent="
          run(async () => {
            await api(
              room.id ? '/meeting-rooms/' + room.id : '/meeting-rooms',
              room.id ? 'PUT' : 'POST',
              { ...room, capacity: room.capacity || null },
            );
            roomEditing = false;
            await references();
          })
        "
      >
        <label>会议室名称<input v-model="room.name" required maxlength="100" /></label
        ><label>地点<input v-model="room.location" maxlength="200" /></label
        ><label>容纳人数<input v-model="room.capacity" type="number" min="1" step="1" /></label
        ><label><input v-model="room.enabled" type="checkbox" />启用会议室</label
        ><button :disabled="busy">保存会议室</button>
      </form>
      <form v-else-if="editing" @submit.prevent="save">
        <label>会议标题<input v-model="form.title" required maxlength="200" /></label
        ><label
          >开始时间（北京时间）<input
            v-model="form.startAt"
            type="datetime-local"
            required
            @change="run(loadOccupancy)" /></label
        ><label
          >结束时间（北京时间）<input
            v-model="form.endAt"
            type="datetime-local"
            required
            @change="run(loadOccupancy)"
        /></label>
        <label
          >预定会议室<select v-model="form.roomId" @change="run(loadOccupancy)">
            <option :value="null">不订会议室（仅日程）</option>
            <option
              v-for="r in rooms.filter((r) => r.enabled || r.id === form.roomId)"
              :key="r.id"
              :value="r.id"
              :disabled="!r.enabled"
            >
              {{ r.name }} · {{ r.location || '未填地点' }} · {{ r.capacity || '不限' }}人{{
                r.enabled ? '' : '（已停用，仅保留原预定）'
              }}
            </option>
          </select></label
        >
        <div v-if="form.roomId">
          <h3>所选会议室占用时段</h3>
          <p v-if="!occupancy.length">当前所选日期没有占用</p>
          <p v-for="o in occupancy" :key="o.id">
            {{ display(o.start_at) }} — {{ display(o.end_at) }} · {{ o.title }} ·
            {{ o.organizer_name }}
          </p>
        </div>
        <label>说明<textarea v-model="form.description" maxlength="2000" /></label
        ><label
          >邀请同事（多选，最多200人，组织者自动参加）<select
            v-model="form.attendeeIds"
            multiple
            size="6"
          >
            <option
              v-for="p in people.filter((p) => p.id !== (detail?.organizer_id || me.id))"
              :key="p.id"
              :value="p.id"
            >
              {{ p.name }} · {{ p.employee_no }}
            </option>
          </select></label
        ><button class="primary" :disabled="busy">保存会议</button>
      </form>
      <div v-else-if="detail">
        <p>{{ display(detail.start_at) }} — {{ display(detail.end_at) }}（北京时间）</p>
        <p>
          会议室：{{ detail.room_name || '未订会议室' }} · 组织者：{{ detail.organizer_name }} ·
          {{ detail.cancelled_at ? '已取消' : '进行中 / 待开始' }}
        </p>
        <p v-if="'description' in detail" style="white-space: pre-wrap">
          说明：{{ detail.description || '未填写' }}
        </p>
        <template v-if="detail.attendees"
          ><h3>参会人（含组织者 {{ detail.organizer_name }}）</h3>
          <p v-for="p in detail.attendees" :key="p.user_id">
            {{ p.name }} · {{ p.employee_no }}
            <span v-if="p.response">{{ responses[p.response] }} {{ p.reason }}</span>
          </p>
          <p v-if="!detail.attendees.length">无其他参会人</p></template
        >
        <p v-else>仅参会人、组织者和管理员可查看说明及完整名单。</p>
        <template v-if="detail.canReply"
          ><label>请假原因（可选）<input v-model="reason" maxlength="300" /></label
          ><button :disabled="busy" @click="reply('ACCEPTED')">接受邀请</button
          ><button :disabled="busy" @click="reply('DECLINED')">请假</button></template
        ><template v-if="detail.canEdit && !detail.cancelled_at"
          ><button :disabled="busy" @click="edit">修改会议</button
          ><button class="danger" :disabled="busy" @click="cancel">取消会议</button></template
        >
      </div>
    </section>
  </div>
</template>
