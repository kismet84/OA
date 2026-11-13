import { ref, onMounted, onUnmounted, watch, type Ref } from 'vue';
import { api, type Row } from './api';
const summary = ref<Row>({});
export function useMessageSummary(active?: Ref<boolean>) {
  let timer: ReturnType<typeof setInterval> | undefined;
  const refresh = async () => {
    if ((active && !active.value) || document.hidden) return;
    try { summary.value = await api('/messages/summary'); } catch { /* Main action owns error feedback. */ }
  };
  onMounted(() => {
    void refresh(); timer=setInterval(refresh,60000);
    window.addEventListener('oa-data-changed',refresh); window.addEventListener('focus',refresh);
    document.addEventListener('visibilitychange',refresh);
  });
  if(active) watch(active,value=>{if(value) void refresh(); else summary.value={};});
  onUnmounted(()=>{clearInterval(timer);window.removeEventListener('oa-data-changed',refresh);window.removeEventListener('focus',refresh);document.removeEventListener('visibilitychange',refresh);});
  return {summary,refresh};
}
export const approvalTotal=(s:Row)=>(s.approvalsRequests||0)+(s.approvalsContracts||0)+(s.approvalsAssets||0);
export const badgeText=(n:number)=>n>99?'99+':String(n);
