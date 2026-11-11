<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { api, binary, download, display, type Row } from './api';
import DataTable from './DataTable.vue';
const props = defineProps<{
  management?: boolean;
  departments?: Row[];
  documentId?: number | null;
}>();
const cats: Record<string, string> = { POLICY: '公司制度', FILE: '通知文件', OTHER: '其他' };
const rows = ref<Row[]>([]),
  detail = ref<Row | null>(null),
  receipts = ref<Row | null>(null),
  editing = ref(false),
  busy = ref(false),
  error = ref(''),
  offset = ref(0);
const filters = reactive({ category: '', keyword: '', publishedFrom: '', publishedTo: '' });
const form = reactive({
  title: '',
  description: '',
  category: 'POLICY',
  departmentId: null as number | null,
  allowDownload: true,
  attachmentIds: [] as string[],
});
const files = ref<Row[]>([]),
  previewFile = ref<Row | null>(null),
  page = ref(1),
  pages = ref(1),
  watermark = ref(''),
  imageUrl = ref('');
const cols = (pairs: string[]) =>
  pairs.map((s) => {
    const [key, label] = s.split(':');
    return { key: key!, label: label! };
  });
function clearPreview() {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
  imageUrl.value = '';
  previewFile.value = null;
}
async function run(fn: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await fn();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busy.value = false;
  }
}
async function load() {
  const q = new URLSearchParams({
    management: String(!!props.management),
    offset: String(offset.value),
    ...filters,
  });
  rows.value = await api('/documents?' + q);
}
async function open(id: number) {
  clearPreview();
  detail.value = null;
  receipts.value = null;
  editing.value = false;
  detail.value = await api('/documents/' + id);
  if (props.management) receipts.value = await api('/documents/' + id + '/receipts');
}
function edit(create = false) {
  clearPreview();
  if (create) detail.value = null;
  const d = detail.value;
  Object.assign(form, {
    title: d?.title || '',
    description: d?.description || '',
    category: d?.category || 'POLICY',
    departmentId: d?.department_id ?? null,
    allowDownload: d?.allow_download ?? true,
  });
  files.value = d ? [...d.attachments] : [];
  editing.value = true;
}
async function upload(e: Event) {
  const input = e.target as HTMLInputElement;
  await run(async () => {
    for (const f of Array.from(input.files || [])) {
      if (files.value.length >= 10) throw new Error('最多 10 份附件');
      const data = new FormData();
      data.append('file', f);
      files.value.push(await api('/attachments', 'POST', data));
    }
  });
  input.value = '';
}
async function save() {
  const r = await api(
    detail.value ? '/documents/' + detail.value.id : '/documents',
    detail.value ? 'PUT' : 'POST',
    { ...form, attachmentIds: files.value.map((f) => f.id) },
  );
  await open(r.id);
  await load();
}
async function status(action: string) {
  await api('/documents/' + detail.value!.id + '/' + action, 'POST');
  await open(detail.value!.id);
  await load();
}
function base(f: Row) {
  return '/documents/' + detail.value!.id + '/attachments/' + f.id;
}
async function showPage(n: number) {
  const blob = await binary(base(previewFile.value!) + '/pages/' + n);
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
  imageUrl.value = URL.createObjectURL(blob);
  page.value = n;
}
async function preview(f: Row) {
  clearPreview();
  const info = await api(base(f) + '/preview');
  previewFile.value = f;
  pages.value = info.pages;
  watermark.value = info.watermark;
  await showPage(1);
  if (props.management) receipts.value = await api('/documents/' + detail.value!.id + '/receipts');
}
onMounted(() =>
  run(async () => {
    await load();
    if (props.documentId) await open(props.documentId);
  }),
);
watch(
  () => props.documentId,
  (id) => {
    if (id) run(() => open(id));
  },
);
onUnmounted(clearPreview);
</script>
<template>
  <section class="card records">
    <h2>文档资料</h2>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="actions">
      <label
        >分类<select v-model="filters.category">
          <option value="">全部分类</option>
          <option v-for="(label, key) in cats" :value="key">{{ label }}</option>
        </select></label
      ><label
        >标题关键字<input
          v-model="filters.keyword"
          maxlength="200"
          @keyup.enter="
            run(async () => {
              offset = 0;
              await load();
            })
          " /></label
      ><label>发布起日<input type="date" v-model="filters.publishedFrom" /></label
      ><label>发布止日<input type="date" v-model="filters.publishedTo" /></label
      ><button
        :disabled="busy"
        @click="
          run(async () => {
            offset = 0;
            await load();
          })
        "
      >
        筛选文档</button
      ><button v-if="management" class="primary" @click="edit(true)">发布文档</button>
    </div>
    <DataTable
      :rows="
        rows.map((r) => ({
          ...r,
          category: cats[r.category],
          status: r.status === 'PUBLISHED' ? '已发布' : '已下架',
        }))
      "
      :columns="cols(['title:标题', 'category:分类', 'status:状态', 'published_at:发布时间'])"
      ><template #actions="{ row }"
        ><button @click="run(() => open(row.id))">查看文档</button></template
      ></DataTable
    >
    <div class="pagination">
      <button
        :disabled="busy || offset === 0"
        @click="
          run(async () => {
            offset -= 200;
            await load();
          })
        "
      >
        上一页</button
      ><span>第 {{ offset / 200 + 1 }} 页</span
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
  <div v-if="editing || detail" class="modal-backdrop">
    <section class="modal" role="dialog" aria-modal="true" aria-label="文档资料详情">
      <div class="modal-header">
        <h2>{{ editing ? (detail ? '修改文档' : '发布文档') : detail?.title }}</h2>
        <button
          aria-label="关闭文档"
          @click="
            editing = false;
            detail = null;
            clearPreview();
          "
        >
          ×
        </button>
      </div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <form v-if="editing" @submit.prevent="run(save)">
        <label>文档标题<input v-model="form.title" required maxlength="200" /></label
        ><label>文档说明<textarea v-model="form.description" maxlength="5000" rows="4" /></label>
        <label
          >文档分类<select v-model="form.category" aria-label="文档分类">
            <option v-for="(label, key) in cats" :value="key">{{ label }}</option>
          </select></label
        >
        <label
          >接收范围<select v-model="form.departmentId" aria-label="接收范围">
            <option :value="null">全员</option>
            <option v-for="d in departments" :value="d.id">{{ d.name }}（含下级部门）</option>
          </select></label
        >
        <label><input type="checkbox" v-model="form.allowDownload" />允许下载水印件</label>
        <label
          >文档附件（1–10 份，PDF/PNG/JPEG，单份最多 10MB）<input
            type="file"
            multiple
            accept=".pdf,.png,.jpg,.jpeg"
            :disabled="busy"
            @change="upload"
        /></label>
        <p v-for="(f, i) in files" :key="f.id">
          {{ f.original_name || f.name }}
          <button type="button" @click="files.splice(i, 1)">移除附件</button>
        </p>
        <p>附件须由当前管理员上传；其他管理员修改时请重新上传附件。</p>
        <button class="primary" :disabled="busy">{{ detail ? '保存文档' : '确认发布文档' }}</button>
      </form>
      <div v-else-if="detail">
        <p>
          {{ cats[detail.category] }} · {{ detail.status === 'PUBLISHED' ? '已发布' : '已下架' }} ·
          {{ display(detail.published_at) }}
        </p>
        <p class="announcement-body">{{ detail.description }}</p>
        <div v-if="management" class="actions">
          <button @click="edit()">修改文档</button
          ><button
            :disabled="busy"
            @click="run(() => status(detail!.status === 'PUBLISHED' ? 'withdraw' : 'republish'))"
          >
            {{ detail.status === 'PUBLISHED' ? '下架文档' : '重新上架' }}
          </button>
        </div>
        <p v-if="!detail.allow_download">仅限带水印预览，禁止下载</p>
        <div v-for="f in detail.attachments" :key="f.id" class="actions">
          <span>{{ f.original_name }}</span
          ><button :disabled="busy" @click="run(() => preview(f))">水印预览</button
          ><button
            v-if="detail.allow_download"
            :disabled="busy"
            @click="
              run(() =>
                download(
                  base(f) + '/download',
                  f.original_name + '-水印.' + (f.media_type === 'application/pdf' ? 'pdf' : 'png'),
                ),
              )
            "
          >
            下载水印件</button
          ><button
            v-if="management"
            @click="run(() => download('/attachments/' + f.id, f.original_name))"
          >
            取回原件
          </button>
        </div>
        <div v-if="previewFile" class="document-preview">
          <h3>{{ previewFile.original_name }}</h3>
          <p>查看水印：{{ watermark }}</p>
          <div class="actions">
            <button :disabled="busy || page <= 1" @click="run(() => showPage(page - 1))">
              上一页预览</button
            ><span>第 {{ page }} / {{ pages }} 页</span
            ><button :disabled="busy || page >= pages" @click="run(() => showPage(page + 1))">
              下一页预览
            </button>
          </div>
          <img
            v-if="imageUrl"
            :src="imageUrl"
            alt="带查看人水印的文档预览"
            style="width: 100%; height: auto"
          />
        </div>
        <template v-if="receipts"
          ><h3>已读 {{ receipts.readCount }} 人 / 未读 {{ receipts.unreadCount }} 人</h3>
          <h4>已读名单</h4>
          <DataTable
            :rows="receipts.read"
            :columns="
              cols([
                'name:姓名',
                'employee_no:工号',
                'department_name:部门',
                'read_at:首次预览时间',
              ])
            " />
          <h4>未读名单</h4>
          <DataTable
            :rows="receipts.unread"
            :columns="cols(['name:姓名', 'employee_no:工号', 'department_name:部门'])" />
          <h4>已移出范围的历史回执</h4>
          <DataTable
            :rows="receipts.historical"
            :columns="cols(['name:姓名', 'employee_no:工号', 'read_at:首次预览时间'])"
        /></template>
      </div>
    </section>
  </div>
</template>
<style scoped>
.actions {
  flex-wrap: wrap;
  align-items: end;
  margin: 12px 0;
}
.actions label {
  flex: 1;
  min-width: 130px;
}
.actions span {
  overflow-wrap: anywhere;
}
.document-preview {
  margin-top: 20px;
}
.modal {
  max-width: 900px;
}
.modal-header h2 {
  overflow-wrap: anywhere;
}
input[type='checkbox'] {
  width: auto;
}
</style>
