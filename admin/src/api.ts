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
    throw new Error('网络连接失败或请求超时，请确认服务已启动后重试');
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: '服务暂不可用，请稍后重试' }));
    if (response.status === 401 && path !== '/auth/login') {
      setToken('');
      window.dispatchEvent(new Event('oa-session-expired'));
    }
    throw new Error(error.message || '操作失败，请检查输入');
  }
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
  if (/^\d{4}-\d{2}-\d{2}T/.test(s)) return s.slice(0, 19).replace('T', ' ');
  return labels[s] || s;
}
