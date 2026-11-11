<script setup lang="ts">
import { display, type Row } from './api';
defineProps<{ rows: Row[]; columns: { key: string; label: string }[] }>();
</script>
<template>
  <div class="table-wrap">
    <table>
      <thead>
        <tr>
          <th v-for="c in columns" :key="c.key">{{ c.label }}</th>
          <th v-if="$slots.actions">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in rows" :key="r.id ?? i">
          <td v-for="c in columns" :key="c.key">
            <span :class="c.key === 'status' ? ['badge', String(r[c.key]).toLowerCase()] : ''">{{
              display(r[c.key])
            }}</span>
          </td>
          <td v-if="$slots.actions">
            <div class="actions"><slot name="actions" :row="r" /></div>
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td :colspan="columns.length + 1" class="empty">暂无记录</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
