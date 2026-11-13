<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref} from 'vue';
import {api,display,type Row} from './api';
const props=defineProps<{detail:Row;path:string}>(),emit=defineEmits<{done:[]}>();
const busy=ref(false),error=ref(''),message=ref(''),now=ref(Date.now());let timer:ReturnType<typeof setInterval>;
onMounted(()=>{timer=setInterval(()=>now.value=Date.now(),1000)});onUnmounted(()=>clearInterval(timer));
const cooling=computed(()=>props.detail.urgeAvailableAt&&Date.parse(props.detail.urgeAvailableAt)>now.value);
async function urge(){if(busy.value)return;busy.value=true;error.value='';try{const r=await api(props.path+'/urge','POST');message.value=r.message;emit('done')}catch(e){error.value=e instanceof Error?e.message:'催办失败'}finally{busy.value=false}}
</script><template><div v-if="detail.canUrge"><button :disabled="busy||cooling" @click="urge">{{busy?'提交中…':'催办'}}</button><span v-if="cooling">可再次催办时间：{{display(detail.urgeAvailableAt)}}</span><p v-if="message" role="status">{{message}}</p><p v-if="error" role="alert" class="error">{{error}}</p></div></template>
