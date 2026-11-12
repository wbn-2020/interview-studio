<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput, NSelect, NSkeleton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { ItemDetail, ItemSummary, Schema, Version } from '../../api/client'
import type { MaterialExcerpt } from '../../api/material'
import * as s from '../../api/standardReviews'
import { questionTextLength } from '../../api/bank'
import { useWorkspaceStore } from '../../state/workspace'
import KnowledgePicker from '../KnowledgePicker.vue'
import MaterialExcerptPicker from '../material/MaterialExcerptPicker.vue'

const props = defineProps<{ disabled: boolean; existing: s.ReviewEvidence[] }>()
const emit = defineEmits<{ add: [value: s.ReviewEvidence]; dirty: [value: boolean] }>()
const store = useWorkspaceStore(), kind = ref<s.BasisKind>('KNOWLEDGE')
const knowledge = shallowRef<ItemDetail>(), version = shallowRef<Version>(), versions = shallowRef<Schema['VersionPage']>()
const material = shallowRef<MaterialExcerpt>(), selection = shallowRef<{ start: number; end: number; quote: string }>()
const busy = ref(false), error = ref(''), hint = ref('')
const external = reactive({ url: '', title: '', locator: '', quote: '', capturedOn: '', sourceDescription: '' })
const original = computed(() => kind.value === 'KNOWLEDGE' ? version.value?.body ?? '' : material.value?.text ?? '')
const localDirty = computed(() => Boolean(selection.value || knowledge.value || material.value || Object.values(external).some(Boolean)))
let alive = true, generation = 0
watch(localDirty, value => emit('dirty', value), { immediate: true })
watch(kind, () => { generation++; knowledge.value = undefined; version.value = undefined; versions.value = undefined; material.value = undefined; selection.value = undefined; busy.value = false; error.value = ''; hint.value = '' }, { flush: 'sync' })
// 登录、权限或父级请求变化只让在途读取失效；确切来源和未加入的选区仍是用户输入。
watch([() => props.disabled, () => store.sessionEpoch], () => { generation++; busy.value = false }, { flush: 'sync' })
onUnmounted(() => { alive = false; generation++; emit('dirty', false) })
const current = (request: number, epoch: number) => alive && !props.disabled && store.sessionActive && request === generation && epoch === store.sessionEpoch
async function chooseKnowledge(item: ItemSummary) {
  if (props.disabled || busy.value || !store.sessionActive) return
  const request = ++generation, epoch = store.sessionEpoch
  busy.value = true; error.value = ''; knowledge.value = undefined; version.value = undefined; versions.value = undefined; selection.value = undefined
  try {
    const value = await read<ItemDetail>(`/knowledge/items/${encodeURIComponent(item.id)}`)
    if (!current(request, epoch)) return
    if (value.id !== item.id || value.status !== 'ACTIVE' || !['NOTE', 'CONCEPT'].includes(value.kind) || value.published.id !== value.currentVersionId || typeof value.published.body !== 'string') throw new Error('所选知识的当前发布版本无法核对，请重新选择。')
    knowledge.value = value; version.value = value.published
  } catch (reason) { if (current(request, epoch)) error.value = errorText(reason) }
  finally { if (current(request, epoch)) busy.value = false }
}
async function loadVersions(before?: number) {
  const item = knowledge.value
  if (!item || props.disabled || busy.value) return
  const request = ++generation, epoch = store.sessionEpoch
  busy.value = true; error.value = ''
  try {
    const page = await read<Schema['VersionPage']>(`/knowledge/items/${encodeURIComponent(item.id)}/versions?limit=20${before === undefined ? '' : `&before=${before}`}`)
    if (!current(request, epoch)) return
    if (!Array.isArray(page.items) || page.items.some(row => typeof row.id !== 'string' || !Number.isInteger(row.number))) throw new Error('发布历史响应无法核对，请重试。')
    versions.value = before === undefined ? page : { ...page, items: [...(versions.value?.items ?? []), ...page.items] }
  } catch (reason) { if (current(request, epoch)) error.value = errorText(reason) }
  finally { if (current(request, epoch)) busy.value = false }
}
async function chooseVersion(id: string) {
  const item = knowledge.value
  if (!item || props.disabled || busy.value || !versions.value?.items.some(row => row.id === id)) return
  const request = ++generation, epoch = store.sessionEpoch
  busy.value = true; error.value = ''; selection.value = undefined
  try {
    const value = await read<Version>(`/knowledge/items/${encodeURIComponent(item.id)}/versions/${encodeURIComponent(id)}`)
    if (!current(request, epoch)) return
    if (value.id !== id || typeof value.body !== 'string') throw new Error('发布版本身份无法核对，请重试。')
    version.value = value
  } catch (reason) { if (current(request, epoch)) error.value = errorText(reason) }
  finally { if (current(request, epoch)) busy.value = false }
}
function chooseMaterial(value: MaterialExcerpt) {
  if (props.disabled) return
  const excerptIds = new Set(props.existing.flatMap(row => row.material ? [row.material.excerptId] : []))
  if (!excerptIds.has(value.id) && excerptIds.size >= 5) { error.value = '一份审阅最多引用 5 个不同材料摘录；同一摘录可选择不同片段。'; return }
  material.value = value; selection.value = undefined; error.value = ''; hint.value = ''
}
function capture(event: Event) {
  if (props.disabled) return
  const target = event.target as HTMLTextAreaElement, raw = original.value
  selection.value = undefined; hint.value = ''
  if (target.value !== raw.replace(/\r\n?/g, '\n') || target.selectionStart === target.selectionEnd) return
  // textarea 规范化换行；映回保存文本，UTF-16 位置不能切开代理对。
  const offset = (position: number) => { let r = 0, d = 0; while (d < position && r < raw.length) { if (raw[r] === '\r' && raw[r + 1] === '\n') r++; r++; d++ } return r }
  const start = offset(target.selectionStart), end = offset(target.selectionEnd), quote = raw.slice(start, end)
  const cutsPair = (at: number) => at > 0 && at < raw.length && /[\uD800-\uDBFF]/.test(raw[at - 1]!) && /[\uDC00-\uDFFF]/.test(raw[at]!)
  if (!quote.trim() || questionTextLength(quote) > 2000 || cutsPair(start) || cutsPair(end)) { hint.value = '请选择 1–2000 个完整字符，且不能只有空白。'; return }
  selection.value = { start, end, quote }
}
function clearInput() {
  if (props.disabled) return
  generation++; knowledge.value = undefined; version.value = undefined; versions.value = undefined; material.value = undefined; selection.value = undefined
  external.url = ''; external.title = ''; external.locator = ''; external.quote = ''; external.capturedOn = ''; external.sourceDescription = ''; error.value = ''; hint.value = ''
}
function add() {
  if (props.disabled || props.existing.length >= 12) return
  const value = s.emptyReviewEvidence(kind.value)
  if (kind.value === 'EXTERNAL_QUOTE') {
    if (!s.externalQuoteValid(external)) { error.value = '请补全外部摘录、合法日期和定位，并填写不含账号、密码、空白或控制字符的 HTTP(S) 地址。'; return }
    value.external = { ...external }
  } else if (kind.value === 'KNOWLEDGE' && knowledge.value && version.value && selection.value) value.knowledge = { itemId: knowledge.value.id, versionId: version.value.id, ...selection.value }
  else if (kind.value === 'MATERIAL_EXCERPT' && material.value && selection.value) value.material = { excerptId: material.value.id, ...selection.value }
  else return
  const key = s.reviewEvidenceLocation
  if (props.existing.some(row => key(row) === key(value))) { error.value = '这处来源已经加入；请在已有证据中补充角色。'; return }
  emit('add', value); selection.value = undefined; knowledge.value = undefined; version.value = undefined; versions.value = undefined; material.value = undefined
  external.url = ''; external.title = ''; external.locator = ''; external.quote = ''; external.capturedOn = ''; external.sourceDescription = ''
  hint.value = '已加入证据，请补全证据角色、取得经过和使用理由，再关联检查项。'; error.value = ''
}
</script>

<template>
  <section class="standard-evidence-picker" aria-label="选择审阅证据来源">
    <label>来源类型<NSelect v-model:value="kind" :disabled="disabled || busy" :options="Object.entries(s.basisKindLabels).map(([value,label])=>({value,label}))" /></label>
    <template v-if="kind === 'KNOWLEDGE'">
      <KnowledgePicker v-if="!knowledge && !disabled && !busy" :disabled="disabled || busy" @choose="chooseKnowledge" />
      <template v-if="knowledge"><div class="row-actions"><strong>{{ version?.title }} · 发布 v{{ version?.number }}</strong><NButton size="small" :disabled="disabled || busy" @click="knowledge = undefined; version = undefined; versions = undefined; selection = undefined">更换知识</NButton><NButton text size="small" :disabled="disabled || busy" @click="loadVersions()">选择其他发布版本</NButton></div>
        <p class="field-help">{{ version?.id === knowledge.currentVersionId ? '当前发布版本' : '历史发布版本，已被后续版本替代；将保留所选旧版原文' }}。草稿不作为审阅依据。</p>
        <template v-if="versions"><NSelect :value="version?.id" :options="versions.items.map(row=>({value:row.id,label:`v${row.number} · ${row.title}`}))" :disabled="disabled || busy" aria-label="选择确切知识发布版本" @update:value="chooseVersion" /><NButton v-if="versions.nextCursor !== null" text size="small" :disabled="disabled || busy" @click="loadVersions(versions.nextCursor)">读取更早发布版本</NButton></template>
      </template>
    </template>
    <template v-else-if="kind === 'MATERIAL_EXCERPT'">
      <MaterialExcerptPicker v-if="!material" :disabled="disabled || busy" search-excerpts @choose="chooseMaterial" />
      <template v-else><div class="row-actions"><strong>{{ material.origin === 'MANUAL' ? '用户手工摘录' : '解析片段摘录' }} · {{ material.locator }}</strong><NButton size="small" :disabled="disabled" @click="material = undefined; selection = undefined">更换摘录</NButton></div><p class="field-help">{{ material.origin === 'MANUAL' ? '原文和定位由用户提供，不表示机器确认了附件原文。' : '引用固定的保存摘录，不会改用最新解析结果。' }} 本次不会重新读取磁盘原件。</p></template>
    </template>
    <div v-else class="external-fields">
      <label>来源标题<NInput v-model:value="external.title" :disabled="disabled" :maxlength="200" /></label><label>公开地址<NInput v-model:value="external.url" :disabled="disabled" :maxlength="2000" placeholder="https://…" /></label>
      <label>明确定位<NInput v-model:value="external.locator" :disabled="disabled" :maxlength="500" placeholder="章节、段落、评论编号等" /></label><label>摘录日期<input v-model="external.capturedOn" type="date" min="1000-01-01" max="9999-12-31" :disabled="disabled" /></label>
      <label class="wide">来源摘录<NInput v-model:value="external.quote" type="textarea" :disabled="disabled" :maxlength="2000" :autosize="{minRows:3,maxRows:8}" /></label><label class="wide">来源性质说明<NInput v-model:value="external.sourceDescription" type="textarea" :disabled="disabled" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" placeholder="说明这是什么记录，以及来源与审阅的关系" /></label>
      <p class="field-help wide">只保存所填地址、定位和摘录；平台不访问、抓取或验证外部页面。</p>
    </div>
    <NSkeleton v-if="busy" text :repeat="3" />
    <template v-if="kind !== 'EXTERNAL_QUOTE' && original"><p class="field-help">在原文中拖选，或按 Shift 加方向键选择 1–2000 个字符，再加入证据。</p><textarea :value="original" readonly :disabled="disabled || busy" rows="8" aria-label="选择审阅依据的原文片段" @select="capture" @keyup="capture" @mouseup="capture" /><blockquote v-if="selection">{{ selection.quote }}</blockquote></template>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p><p v-if="hint" class="field-help" role="status">{{ hint }}</p>
    <div class="row-actions"><NButton :disabled="disabled || busy || existing.length >= 12 || (kind !== 'EXTERNAL_QUOTE' && !selection)" @click="add">加入这条证据</NButton><NButton v-if="localDirty" text :disabled="disabled || busy" @click="clearInput">清空本次来源输入</NButton></div>
  </section>
</template>

<style scoped>
.standard-evidence-picker { display:grid; gap:13px; min-width:0; padding:16px 0; border-top:1px solid var(--line); }label { display:grid; gap:7px; font-size:13px; }strong,blockquote { overflow-wrap:anywhere; }blockquote { margin:0; padding:12px; background:var(--surface-soft); white-space:pre-wrap; max-width:75ch; font-size:13px; }.external-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:13px; }.wide { grid-column:1/-1; }textarea,input[type=date] { box-sizing:border-box; width:100%; border:1px solid var(--line); border-radius:6px; padding:10px; background:var(--surface); color:var(--ink); font:inherit; font-size:13px; }textarea { resize:vertical; line-height:1.7; }textarea:focus-visible,input:focus-visible { outline:2px solid var(--brand); outline-offset:2px; }.standard-evidence-picker>.n-button { justify-self:start; }@media(max-width:600px) { .external-fields { grid-template-columns:1fr; } }
</style>
