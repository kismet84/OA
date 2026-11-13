import {prepareAttachment, uploadStatus} from './upload';
export interface Actor {
  id: number;
  name: string;
  roles: string;
  mustChange: boolean;
  version: number;
}
export type Row = Record<string, any>;
// Separate browser sessions for the employee and management entries.
const tokenKey = /^\/staff(?:\/|$)/.test(location.pathname) ? 'oa-staff-token' : 'oa-token';
let token = sessionStorage.getItem(tokenKey) || '';
export function setToken(value: string) {
  token = value;
  value ? sessionStorage.setItem(tokenKey, value) : sessionStorage.removeItem(tokenKey);
}
export async function api<T = any>(path: string, method = 'GET', body?: unknown): Promise<T> {
  if(path==='/attachments' && body instanceof FormData) await prepareAttachment(body);
  let response: Response;
  try {
    response = await fetch('/api' + path, {
      signal: AbortSignal.timeout(20000),
      method,
      headers: {
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
        ...(body instanceof FormData || body === undefined
          ? {}
          : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
    });
  } catch {
    uploadStatus.value='';
    throw new Error('网络连接失败或请求超时，请确认服务已启动后重试');
  }
  if(path==='/attachments') uploadStatus.value='';
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: '服务暂不可用，请稍后重试' }));
    if (response.status === 401 && path !== '/auth/login') {
      setToken('');
      window.dispatchEvent(new Event('oa-session-expired'));
    }
    throw new Error(error.message || '操作失败，请检查输入');
  }
  if(method!=='GET'&&!path.startsWith('/auth/')) window.dispatchEvent(new Event('oa-data-changed'));
  return response.json();
}
export async function download(path: string, filename: string) {
  let response: Response;
  try {
    response = await fetch('/api' + path, {
      signal: AbortSignal.timeout(60000),
      headers: { Authorization: 'Bearer ' + token },
    });
  } catch {
    throw new Error('下载连接失败或超时，请稍后重试');
  }
  if (!response.ok) throw new Error((await response.json()).message || '下载失败');
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
export const labels: Record<string, string> = {
  RESERVE_VISITOR: '预约访客', CANCEL_VISITOR: '取消访客预约', CHECKIN_VISITOR: '访客签到', CHECKOUT_VISITOR: '访客签离', REGISTER_TEMP_VISITOR: '临时来访登记', EXPORT_VISITORS: '导出访客台账',
  PUBLISH_DOCUMENT:'发布文档', UPDATE_DOCUMENT:'修改文档', WITHDRAW_DOCUMENT:'下架文档', REPUBLISH_DOCUMENT:'重新上架文档',
  LIVE:'现场实时', WARMUP_CACHE:'预热缓存', LAST_KNOWN:'系统缓存', STALE_FALLBACK:'超时旧缓存', UNKNOWN:'未知/历史记录', REASSIGN_CONTRACT:'改派合同审批人', REASSIGN_ASSET:'改派资产审批人', REASSIGN_REQUEST:'改派审批人',
  CONTRACT:'合同', SAVE_CONTRACT:'登记合同', DELETE_CONTRACT:'删除合同草稿', SUBMIT_CONTRACT:'提交合同', APPROVE_CONTRACT:'合同通过', REJECT_CONTRACT:'合同驳回', WITHDRAW_CONTRACT:'撤回合同', ARCHIVE_CONTRACT:'归档合同', TERMINATE_CONTRACT:'终止合同', EXPORT_CONTRACTS:'导出合同', PREVIEW_CONTRACT_FILE:'预览合同附件', DOWNLOAD_CONTRACT_FILE:'下载合同附件',
  ASSET: '资产领用', READY:'待发放', ISSUED:'已发放', IN_USE:'使用中', RETURNED:'已归还',
  CREATE_ASSET_CATEGORY:'新增资产分类', UPDATE_ASSET_CATEGORY:'编辑资产分类', SAVE_ASSET:'登记资产', ADJUST_ASSET:'调整资产', APPLY_ASSET:'申请领用', APPROVE_ASSET:'审批领用', ISSUE_ASSET:'发放资产', WITHDRAW_ASSET:'撤回领用', REQUEST_ASSET_RETURN:'登记归还', CONFIRM_ASSET_RETURN:'确认归还',
  TASK: '任务', REQUEST: '审批单', TODO: '待处理', IN_PROGRESS: '进行中', COMPLETED: '已完成',
  CREATE_TASK:'创建任务', UPDATE_TASK:'修改任务', COMPLETE_TASK:'完成任务', CANCEL_TASK:'取消任务', RESTORE_TASK:'恢复任务', FOLLOW_TASK:'跟进任务', STATUS_TASK:'任务状态变更',
  CREATE_MEETING: '创建会议',
  UPDATE_MEETING: '修改会议',
  CANCEL_MEETING: '取消会议',
  REPLY_MEETING: '回复参会邀请',
  SAVE_MEETING_ROOM: '维护会议室',
  DISABLE_MEETING_ROOM: '停用会议室',
  DELETE_MEETING_ROOM: '删除会议室',
  PUBLISH_ANNOUNCEMENT: '发布公告',
  WITHDRAW_ANNOUNCEMENT: '撤回公告',
  UNPAID: '待支付',
  PAID: '已支付',
  MARK_EXPENSE_PAID: '标记报销已支付',
  RESET_EXPENSE_UNPAID: '报销改回待支付',
  EMPLOYEE: '员工',
  SUPERVISOR: '直属上级',
  DEPT_HEAD: '部门负责人',
  ADMIN: '管理员',
  FINANCE: '财务/行政',
  OVERTIME: '加班',
  CORRECTION: '补卡',
  OVERTIME_APPROVED: '加班批准计入',
  OVERTIME_CANCELLED: '加班撤销退回',
  LEAVE_APPROVED: '请假批准扣减',
  LEAVE_CANCELLED: '请假撤销返还',
  ADMIN_SET: '管理员设置',
  LEAVE: '请假',
  TRAVEL: '出差',
  OUTING: '外出',
  EXPENSE: '报销',
  NORMAL: '正常打卡',
  FIELD: '出勤',
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
  CANCELLED: '已撤销',
  SSID: '公司 Wi-Fi',
  IP: '出口 IP',
  LOGOUT: '退出登录',
  LOGIN: '登录',
  CHANGE_PASSWORD: '修改密码',
  SAVE_USER: '维护账号',
  SAVE_DEPARTMENT: '维护部门',
  ADD_NETWORK: '登记网络',
  DELETE_NETWORK: '移除网络',
  SAVE_LEAVE_TYPE: '配置假期',
  SET_REMAINING_BALANCE: '设置余额',
  SAVE_WORKFLOW: '配置分流',
  SUBMIT_REQUEST: '提交申请',
  APPROVE_REQUEST: '批准申请',
  REJECT_REQUEST: '驳回申请',
  CANCEL_REQUEST: '撤回/撤销申请',
  UPLOAD_ATTACHMENT: '上传证明',
  SIMULATE_PUNCH: '模拟打卡',
  EXPORT_REQUESTS: '导出审批',
  EXPORT_PUNCHES: '导出打卡',
};
export function display(v: unknown): string {
  if (v == null || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  const s = String(v);
  if (/^\d{4}-\d{2}-\d{2}T/.test(s)) { const d=new Date(s); return Number.isNaN(d.getTime())?s:s.match(/Z$|[+-]\d\d:\d\d$/)?d.toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):s.slice(0,19).replace('T',' '); }
  return labels[s] || s;
}

export async function binary(path: string): Promise<Blob> {
  const response = await fetch('/api'+path, {headers:{Authorization:'Bearer '+token},cache:'no-store',signal:AbortSignal.timeout(60000)});
  if(!response.ok) throw new Error((await response.json()).message || '预览失败');
  return response.blob();
}
