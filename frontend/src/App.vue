<script setup>
import { NConfigProvider, NMessageProvider, NDialogProvider, zhCN, dateZhCN } from 'naive-ui'
import AppLayout from '@/components/AppLayout.vue'
</script>

<template>
  <n-config-provider :locale="zhCN" :date-locale="dateZhCN">
    <n-message-provider>
      <n-dialog-provider>
        <AppLayout />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
