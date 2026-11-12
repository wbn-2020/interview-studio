<script setup lang="ts">
import { computed, onActivated, onDeactivated, ref } from 'vue'
import { NButton } from 'naive-ui'
import { useRouter } from 'vue-router'
import type { MemoryRelatedConcept, MemorySourceReference } from '../../api/memory'
import { memoryIdValid, memorySourceStateLabels } from '../../api/memory'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ references: MemorySourceReference[]; concepts: MemoryRelatedConcept[]; disabled?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter(), active = ref(true)
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
function open(itemId: string, versionId?: string, graph = false) {
  if (!readable.value || !memoryIdValid(itemId) || (versionId !== undefined && !memoryIdValid(versionId))) return
  void router.push({ name: 'knowledge', query: { item: itemId, ...(versionId ? { version: versionId } : {}), ...(graph ? { view: 'graph' } : {}) } })
}
</script>

<template>
  <section class="memory-sources" aria-label="回忆卡来源与相关概念">
    <p v-if="!readable" class="notice" role="status">当前访问条件限制资料读取，来源与概念暂时遮挡。</p>
    <div v-else class="sources-content">
      <section aria-label="确切来源版本"><h3>来源依据</h3><p class="field-help">保留制卡时选择的知识版本。正文变化也可能只是排版调整，需要核对后判断其影响。</p>
        <p v-if="!references.length" class="field-help">尚未选择来源。</p>
        <ol v-else class="source-list"><li v-for="source in references" :key="`${source.itemId}/${source.versionId}`"><div class="source-heading"><strong>{{ source.title }}</strong><span class="field-help">{{ source.kind === 'NOTE' ? '笔记' : '概念' }} · v{{ source.number }}</span></div><p class="field-help">{{ memorySourceStateLabels[source.state] }}{{ source.bodyChanged ? ' · 正文有变化，请核对' : '' }}</p><p v-if="source.state === 'SUPERSEDED' && !source.bodyChanged" class="field-help">已有较新发布版；当前已发布正文与所引版本相同。</p><div class="row-actions"><NButton text size="small" @click="open(source.itemId, source.versionId)">查看引用版本</NButton><NButton v-if="source.state !== 'CURRENT'" text size="small" @click="open(source.itemId)">查看知识当前状态</NButton></div></li></ol>
      </section>
      <section aria-label="相关概念"><h3>相关概念</h3><p class="field-help">用于分类、发现与星图导航；关联概念独立于来源依据。</p><p v-if="!concepts.length" class="field-help">尚未关联概念。</p><ul v-else class="concept-list"><li v-for="concept in concepts" :key="concept.itemId"><span>{{ concept.title }}<small v-if="concept.status === 'ARCHIVED'"> · 已归档</small></span><div class="row-actions"><NButton text size="small" @click="open(concept.itemId)">查看概念</NButton><NButton v-if="concept.status === 'ACTIVE'" text size="small" @click="open(concept.itemId, undefined, true)">在星图中查看</NButton></div></li></ul></section>
    </div>
  </section>
</template>

<style scoped>
.memory-sources, .sources-content { display: grid; gap: 22px; min-width: 0; } section > h3 { margin: 0 0 8px; font-size: 14px; } p { margin: 0; }
.source-list, .concept-list { display: grid; gap: 0; list-style: none; padding: 0; margin: 12px 0 0; }
.source-list > li { display: grid; gap: 8px; padding: 12px 0; border-top: 1px solid var(--line); min-width: 0; }
.source-heading { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; flex-wrap: wrap; }.source-heading strong { font-size: 13px; overflow-wrap: anywhere; }
.concept-list > li { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 8px 15px; padding: 10px 0; border-top: 1px solid var(--line); font-size: 13px; overflow-wrap: anywhere; }
small { color: var(--muted); }.row-actions { display: flex; flex-wrap: wrap; gap: 8px 14px; }
</style>
