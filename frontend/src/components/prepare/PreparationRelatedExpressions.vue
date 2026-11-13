<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { ExpressionContent, PreparationImpactPage, PreparationVersion } from '../../api/preparation'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MarkdownPreview from '../MarkdownPreview.vue'
import RIcon from '../../replica/RIcon.vue'

const props = defineProps<{ factId: string }>()
const emit = defineEmits<{ open: [id: string, versionId?: string] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), route = useRoute(), router = useRouter()
const active = ref(true), visible = ref(document.visibilityState === 'visible')
const readable = computed(() => active.value && visible.value && store.sessionActive && !access.checking && !access.challenge
  && !access.restricted && !store.changes.get('training-access-command')?.locked)
const links = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'fact-expression-links', props.factId]), enabled: readable,
  queryFn: () => read<PreparationImpactPage>(`/prepare/items/${props.factId}/impacts?limit=20`) })
const expressions = computed(() => links.data.value?.items.filter(entry => entry.kind === 'EXPRESSION') ?? [])
const selected = computed(() => typeof route.query.relatedExpression === 'string'
  ? expressions.value.find(entry => entry.id === route.query.relatedExpression) : expressions.value[0])
const version = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'fact-expression-preview', props.factId, selected.value?.id, selected.value?.versionId]),
  enabled: computed(() => readable.value && Boolean(selected.value)),
  queryFn: () => read<PreparationVersion>(`/prepare/items/${selected.value!.id}/versions/${selected.value!.versionId}`) })
const body = computed(() => version.data.value && 'body' in version.data.value.content ? (version.data.value.content as ExpressionContent).body : '')
function choose(id: string) { if (readable.value) void router.replace({ query: { ...route.query, relatedExpression: id } }) }
function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onActivated(() => { active.value = true; visibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => document.removeEventListener('visibilitychange', visibilityChanged))
</script>

<template>
  <section class="related-expressions">
    <h3>表达素材</h3>
    <p v-if="!readable" class="field-help">请恢复资料访问后查看关联表达。</p>
    <template v-else>
      <p v-if="links.isFetching.value" class="field-help" role="status">正在读取关联表达…</p><p v-if="links.error.value" class="notice error">{{ errorText(links.error.value) }}<NButton text @click="links.refetch()">重试</NButton></p>
      <button v-for="entry in expressions" :key="entry.id" :class="['related-expression', { selected: selected?.id === entry.id }]" @click="choose(entry.id)"><RIcon name="file-text" :size="25" /><span>{{ entry.title }}</span><small v-if="entry.reviewRequired">待复核</small><RIcon name="chevron-right" :size="20" /></button>
      <p v-if="links.data.value && !expressions.length" class="field-help">当前已读关联中没有表达素材。</p>
      <div v-if="selected" class="related-expression-preview"><h4>{{ selected.title }}<span v-if="version.data.value">v{{ version.data.value.number }}</span></h4><p v-if="version.isFetching.value" class="field-help" role="status">正在读取确切版本…</p><p v-if="version.error.value" class="notice error">{{ errorText(version.error.value) }}</p><MarkdownPreview v-if="version.data.value" :value="body" /><p v-if="selected.reviewRequired" class="field-help">依据已有变化，表达内容仍待复核。</p><NButton :disabled="!version.data.value" @click="emit('open', selected.id, selected.versionId)"><RIcon name="external-link" :size="20" />打开确切版本</NButton></div>
      <p v-if="links.data.value?.nextCursor" class="field-help">仍有后续关联，请到来源与影响继续读取。</p><p class="related-expression-boundary"><RIcon name="info" :size="21" />事实与表达分开保存</p>
    </template>
  </section>
</template>

<style scoped>
.related-expressions { order: -1; }.related-expressions h3 { font-size: 25px; }.related-expression { display: flex; align-items: center; gap: 11px; width: 100%; padding: 16px 10px; border: 1px solid transparent; border-radius: 13px; text-align: left; font-size: 19px; }.related-expression > svg { flex: none; color: #7ebcba; }.related-expression > span { flex: 1; overflow-wrap: anywhere; }.related-expression > small { font-size: 12px; color: var(--brand); }.related-expression.selected { background: var(--brand-soft); border-color: var(--brand); }.related-expression.selected > svg:first-child { color: var(--brand-strong); }.related-expression-preview { border-top: 1px solid var(--line); padding-top: 23px; display: grid; gap: 18px; }.related-expression-preview h4 { font-size: 23px; font-weight: 500; margin: 0; }.related-expression-preview h4 span { margin-left: 9px; color: var(--brand-strong); font-size: 16px; }.related-expression-preview :deep(.markdown-preview) { font-size: 19px; line-height: 1.8; max-height: 170px; overflow-y: auto; }.related-expression-preview :deep(.n-button__content) { gap: 8px; }.related-expression-boundary { display: flex; align-items: start; gap: 10px; padding-top: 20px; color: var(--muted); font-size: 15px; }.related-expression-boundary svg { flex: none; margin-top: 3px; }
</style>
