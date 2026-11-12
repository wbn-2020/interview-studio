<script setup lang="ts">
import AiAuthorizationManager from '../components/ai/AiAuthorizationManager.vue'
</script>

<template>
  <header class="page-heading"><div><h1>AI 授权管理</h1><p class="muted">查看持续授权的适用范围、有效状态，并撤回不再需要的授权。</p></div></header>
  <p class="field-help">独立训练期间也可管理授权。此处只展示授权元数据；返回原任务查看完整清单时，仍需核对训练的资料使用条件。</p>
  <AiAuthorizationManager />
</template>
