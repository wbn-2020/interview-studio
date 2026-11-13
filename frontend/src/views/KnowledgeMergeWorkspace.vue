<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import type { ConceptMergeResult } from '../api/knowledgeMerge'
import KnowledgeConceptMerge from '../components/KnowledgeConceptMerge.vue'
const route = useRoute(), workspace = useWorkspaceStore(), access = useTrainingAccessStore()
const sourceId = computed(() => typeof route.query.source === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(route.query.source) ? route.query.source : undefined)
const enabled = computed(() => workspace.sessionActive && !access.checking && !access.restricted && !access.challenge)
const completed = ref<ConceptMergeResult>()
</script>
<template>
  <header class="page-heading"><div><p class="section-label">知识整理</p><h1>合并重复概念</h1><p class="muted">先查看双方原文、关系与影响，再确认目标概念的整理信息。</p></div><RouterLink class="text-link" :to="{ name: 'knowledge' }">返回知识库</RouterLink></header>
  <KnowledgeConceptMerge :source-id="sourceId" :enabled="enabled" @merged="completed = $event" />
  <p v-if="completed" class="notice" role="status">合并已保存。<RouterLink class="text-link" :to="{ name: 'knowledge', query: { item: completed.target.id } }">打开目标概念</RouterLink></p>
</template>
