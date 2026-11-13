<script setup lang="ts">
import {onMounted,ref} from 'vue';
import {api,type Row} from './api';
const props=defineProps<{path:string;ownerId:number;workflow?:boolean}>();
const emit=defineEmits<{done:[]}>();
const users=ref<Row[]>([]),approverId=ref(''),note=ref(''),error=ref(''),busy=ref(false);
onMounted(async()=>{try{users.value=(await api<Row[]>('/admin/users')).filter(u=>u.enabled&&u.id!==props.ownerId)}catch(e){error.value=String(e)}});
async function act(action:string,approved?:boolean){
 if(busy.value)return;
 if(!note.value.trim()){error.value='请填写管理员操作说明';return}
 if(action==='reassign'&&!approverId.value){error.value='请选择在职审批人';return}
 if(action!=='reassign'&&!window.confirm('确认管理员代批'+(approved?'同意':'驳回')+'？'))return;
 busy.value=true;error.value='';
 try{await api(props.path+'/'+action,'POST',action==='reassign'?{approverId:Number(approverId.value),note:note.value}:props.workflow?{approve:approved,comment:note.value}:{approved,note:note.value});note.value='';emit('done')}
 catch(e){error.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}
}
</script>
<template><fieldset><legend>管理员审批处理</legend><p>改派仅影响当前步骤，历史指派记录保留；代批会记录管理员本人。</p>
<p v-if="error" role="alert" class="error">{{error}}</p>
<label>新审批人（在职员工）<select v-model="approverId"><option value="">请选择</option><option v-for="u in users" :key="u.id" :value="u.id">{{u.name}}（{{u.employee_no}}）</option></select></label>
<label>管理员操作说明<textarea v-model="note" maxlength="1000" /></label>
<div class="actions"><button type="button" :disabled="busy" @click="act('reassign')">改派审批人</button><button type="button" :disabled="busy" @click="act(workflow?'decision':'proxy-approve',true)">管理员代批同意</button><button type="button" :disabled="busy" @click="act(workflow?'decision':'proxy-approve',false)">管理员代批驳回</button></div>
</fieldset></template>
