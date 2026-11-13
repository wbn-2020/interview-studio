<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import type { ConceptMergeResult } from '../api/knowledgeMerge'
import KnowledgeConceptMerge from '../components/KnowledgeConceptMerge.vue'
import RIcon from '../replica/RIcon.vue'
const route = useRoute(), workspace = useWorkspaceStore(), access = useTrainingAccessStore()
const sourceId = computed(() => typeof route.query.source === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(route.query.source) ? route.query.source : undefined)
const enabled = computed(() => workspace.sessionActive && !access.checking && !access.restricted && !access.challenge)
const completed = ref<ConceptMergeResult>()
</script>
<template>
  <section class="merge-workspace">
  <header class="page-heading"><div><h1>合并重复概念</h1><p class="muted">核对原文与关系，保留清晰的知识入口</p></div><RouterLink class="merge-back-link" :to="{ name: 'knowledge', query: { view: 'list' } }"><RIcon name="arrow-left" :size="21" />返回知识库</RouterLink></header>
  <nav class="merge-navigation" aria-label="知识管理"><RouterLink to="/knowledge"><RIcon name="star" />星图</RouterLink><RouterLink :to="{ name: 'knowledge', query: { view: 'list' } }"><RIcon name="list" />列表</RouterLink><RouterLink to="/knowledge/boards"><RIcon name="square" />白板</RouterLink><span class="active"><RIcon name="share-2" />概念合并</span><RouterLink to="/knowledge/packages"><RIcon name="folder" />知识包导入</RouterLink></nav>
  <div class="merge-workspace-body">
  <KnowledgeConceptMerge :source-id="sourceId" :enabled="enabled" @merged="completed = $event" />
  <aside class="merge-guide"><h2><RIcon name="shield-check" :size="26" />合并前核对</h2><ol><li><strong>选择两个概念</strong><p>源概念归档，目标概念保留正文与版本。</p></li><li><strong>预览影响</strong><p>核对双方原文、已有关系与引用，再整理目标的主题、别名和关联笔记。</p></li><li><strong>明确确认</strong><p>历史证据保留原身份。确认合并后没有一键撤销。</p></li></ol><RouterLink :to="{ name: 'knowledge', query: { view: 'list' } }"><RIcon name="book-open" :size="23" />先回到知识库核对原文<RIcon name="chevron-right" :size="19" /></RouterLink></aside>
  </div>
  <p v-if="completed" class="notice" role="status">合并已保存。<RouterLink class="text-link" :to="{ name: 'knowledge', query: { item: completed.target.id } }">打开目标概念</RouterLink></p>
  </section>
</template>

<style scoped>
.merge-workspace { max-width: 1320px; }.merge-back-link { display: flex; align-items: center; gap: 10px; padding: 12px 20px; border: 1px solid var(--line); border-radius: 26px; color: var(--muted); font-size: 18px; }.merge-navigation { display: flex; gap: 9px; margin: 10px 0 24px; padding-bottom: 17px; border-bottom: 1px solid var(--line); overflow-x: auto; }.merge-navigation > :is(a, span) { display: flex; align-items: center; gap: 10px; padding: 10px 19px; border-radius: 24px; font-size: 19px; color: var(--muted); white-space: nowrap; }.merge-navigation .active { color: #ffe9ce; background: linear-gradient(125deg, #986535a0, #4d321f70); border: 1px solid #996b4188; }.merge-workspace-body { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 22px; align-items: start; }.merge-guide { padding: 26px 23px; border: 1px solid var(--line); border-radius: 22px; background: linear-gradient(125deg, #2c241b90, #18141099); }.merge-guide h2 { display: flex; align-items: center; gap: 10px; font-size: 23px; }.merge-guide ol { list-style: none; counter-reset: merge; margin: 26px 0; padding: 0; }.merge-guide li { counter-increment: merge; position: relative; padding-left: 36px; margin-bottom: 28px; }.merge-guide li::before { content: counter(merge); position: absolute; left: 0; top: 1px; width: 25px; height: 25px; display: grid; place-items: center; border-radius: 50%; color: #f5bf83; background: #9a66293b; border: 1px solid #8b633f; font-size: 14px; }.merge-guide strong { font-size: 18px; font-weight: 500; }.merge-guide p { font-size: 15px; color: var(--muted); margin-top: 9px; line-height: 1.8; }.merge-guide > a { display: flex; align-items: center; gap: 8px; padding-top: 20px; border-top: 1px solid var(--line); color: #d4b08a; font-size: 15px; }
@media (max-width: 1100px) { .merge-workspace-body { grid-template-columns: 1fr; }.merge-guide { display: none; } }
</style>
