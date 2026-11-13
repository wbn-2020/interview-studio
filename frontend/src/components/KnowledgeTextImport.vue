<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox } from 'naive-ui'
import { currentTrainingAccessEpoch, errorText } from '../api/client'
import type { ItemSummary } from '../api/client'
import type { TextImport } from '../api/knowledge'
import { inspectImportLinks, rewriteImportLinks } from '../api/knowledgeTextLinks'
import type { ImportLinkPlan } from '../api/knowledgeTextLinks'
import { inspectImportMetadata, removeImportFrontmatter } from '../api/knowledgeTextMetadata'
import type { ImportMetadata } from '../api/knowledgeTextMetadata'
import KnowledgePicker from './KnowledgePicker.vue'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

const props = defineProps<{ enabled: boolean }>()
const emit = defineEmits<{ apply: [value: TextImport]; cancel: []; state: [dirty: boolean] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const element = ref<HTMLElement>(), active = ref(true), elementVisible = ref(false)
const documentVisible = ref(document.visibilityState === 'visible')
const prepared = shallowRef<TextImport & { sessionEpoch: number; accessEpoch: number; byteSize: number }>()
const reading = ref(false), problem = ref(''), cleared = ref(false)
const linkPlan = shallowRef<ImportLinkPlan>(), choosing = ref<string>()
const targets = ref<Record<string, { id: string; title: string }>>({})
const metadata = shallowRef<ImportMetadata>(), useMetadataTitle = ref(false), removeMetadata = ref(false)
const useMetadataTags = ref(false)
const tagSelection = computed(() => {
  const fields = metadata.value?.fields.filter(field => field.key === 'tags' || field.key === 'tag') ?? []
  const tags = fields.flatMap(field => field.values ?? [])
  if (!fields.length) return { tags, problem: '没有可采用的tags／tag属性。' }
  if (fields.some(field => field.state !== 'DISPLAY') || !tags.length || tags.length > 20
    || tags.some(tag => !tag.trim() || tag.length > 40 || /[\u0000-\u001f\u007f]/.test(tag)
      || !/^(?:[\u0000-\ud7ff\ue000-\uffff]|[\ud800-\udbff][\udc00-\udfff])*$/.test(tag))) {
    return { tags: [], problem: '标签须整体为1至20个非空字符串，每项原始长度最多40字符且无控制符或无效Unicode；未截断或部分采用。' }
  }
  return { tags, problem: '' }
})
const readable = computed(() => active.value && props.enabled && store.sessionActive && documentVisible.value
  && elementVisible.value && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const dirty = computed(() => reading.value || Boolean(prepared.value))
const mappedCount = computed(() => linkPlan.value?.links.reduce((count, link) => count
  + (targets.value[link.key] ? link.occurrences.length : 0), 0) ?? 0)
const rewritten = computed(() => {
  if (!linkPlan.value || !mappedCount.value) return undefined
  try {
    const body = rewriteImportLinks(linkPlan.value, Object.fromEntries(Object.entries(targets.value).map(([key, target]) => [key, target.id])))
    return { body, error: '' }
  } catch (error) { return { body: '', error: errorText(error) } }
})
const processed = computed(() => {
  const value = prepared.value
  if (!value || !mappedCount.value && !useMetadataTitle.value && !removeMetadata.value && !useMetadataTags.value) return undefined
  let body = rewritten.value?.body ?? value.body
  let title = value.title
  let tags: string[] = []
  try {
    if (rewritten.value?.error) throw new Error(rewritten.value.error)
    if (useMetadataTitle.value || removeMetadata.value || useMetadataTags.value) {
      const selected = metadata.value
      if (!selected || selected.problem || selected.source !== value.body) throw new Error('元数据预览已失效，请重新解析。')
      if (useMetadataTitle.value) {
        if (!selected.title) throw new Error('元数据标题不可采用，请保留文件名标题。')
        title = selected.title
      }
      if (useMetadataTags.value) {
        if (tagSelection.value.problem) throw new Error(tagSelection.value.problem)
        tags = [...tagSelection.value.tags]
      }
      if (removeMetadata.value) body = removeImportFrontmatter(selected, body)
    }
    return { title, body, tags, error: body.length > 100000 ? '处理后的正文超过100000字符，不能填入；请减少映射或保留原文。' : '' }
  } catch (error) { return { title, body, tags, error: errorText(error) } }
})
let alive = true, generation = 0, observer: MutationObserver | undefined

watch(dirty, value => emit('state', value), { immediate: true, flush: 'sync' })
function clear() {
  generation++; prepared.value = undefined; reading.value = false; problem.value = ''
  linkPlan.value = undefined; choosing.value = undefined; targets.value = {}
  metadata.value = undefined; useMetadataTitle.value = false; removeMetadata.value = false
  useMetadataTags.value = false
}
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => {
  if (dirty.value) cleared.value = true
  clear()
}, { flush: 'sync' })
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
function elementVisibilityChanged() {
  const node = element.value
  elementVisible.value = Boolean(node && node.getClientRects().length && !node.closest('[hidden], [inert], [aria-hidden="true"]'))
  for (let ancestor: HTMLElement | null = node ?? null; ancestor && elementVisible.value; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor)
    if (style.visibility === 'hidden' || style.visibility === 'collapse') elementVisible.value = false
  }
}
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged); elementVisibilityChanged()
  // 弹层或父级隐藏时不保留文件预览；已填入创建表单的输入由原草稿保护负责。
  observer = new MutationObserver(elementVisibilityChanged)
  for (let node: HTMLElement | null = element.value ?? null; node; node = node.parentElement) {
    observer.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden'] })
  }
})
onActivated(() => { active.value = true; visibilityChanged(); elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; clear(); emit('state', false)
  observer?.disconnect(); document.removeEventListener('visibilitychange', visibilityChanged)
})
async function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  visibilityChanged(); elementVisibilityChanged()
  if (!readable.value || reading.value || !files.length) return
  clear(); cleared.value = false
  const selected = files[0]!
  if (files.length !== 1 || !/\.(md|markdown|txt)$/i.test(selected.name)
    || selected.name.length > 200 || /[\u0000-\u001f\u007f/\\]/.test(selected.name)
    || !Number.isSafeInteger(selected.size) || selected.size < 1 || selected.size > 400000) {
    problem.value = '请选择一份1至400000字节的 .md、.markdown 或 .txt 文件，文件名最多200字符且不能包含路径或控制字符。'
    return
  }
  const ticket = generation, sessionEpoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  const current = () => alive && readable.value && ticket === generation && sessionEpoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && document.visibilityState === 'visible'
  reading.value = true
  try {
    const buffer = await selected.arrayBuffer()
    elementVisibilityChanged()
    if (!current()) return
    if (buffer.byteLength !== selected.size || buffer.byteLength > 400000) throw new Error('实际读取大小无法核对，请重新选择文件。')
    let body: string
    try {
      // 严格UTF-8解码拒绝损坏字节；保留BOM及全部原换行，不以替换字符或trim静默改写原文。
      body = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(buffer)
    } catch { throw new Error('文件不是有效的UTF-8文本，请在原编辑器中另存为UTF-8后重选。') }
    if (body.length > 100000) throw new Error('全文超过100000字符，不能截断导入；请先在原编辑器中拆分文件。')
    if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(body)) throw new Error('文本含不支持的控制字符或二进制内容，不能作为知识正文导入。')
    const sourceSha256 = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer)),
      byte => byte.toString(16).padStart(2, '0')).join('')
    elementVisibilityChanged()
    if (!current()) return
    prepared.value = { fileName: selected.name, title: selected.name.replace(/\.(md|markdown|txt)$/i, '').trim() || '导入笔记',
      source: body, sourceSha256, body, byteSize: buffer.byteLength, sessionEpoch, accessEpoch }
  } catch (error) {
    if (current()) problem.value = errorText(error)
  } finally { if (alive && ticket === generation) reading.value = false }
}
function inspectLinks() {
  visibilityChanged(); elementVisibilityChanged()
  if (!readable.value || reading.value || !prepared.value) return
  choosing.value = undefined; targets.value = {}
  try { linkPlan.value = inspectImportLinks(prepared.value.body) }
  catch { linkPlan.value = { source: prepared.value.body, links: [], problem: '链接结构无法安全解析，未改写正文；仍可保留原文导入。' } }
}
function inspectMetadata() {
  visibilityChanged(); elementVisibilityChanged()
  if (!readable.value || reading.value || !prepared.value) return
  useMetadataTitle.value = false; removeMetadata.value = false
  useMetadataTags.value = false
  metadata.value = inspectImportMetadata(prepared.value.body)
}
function chooseTarget(item: ItemSummary) {
  visibilityChanged(); elementVisibilityChanged()
  const key = choosing.value, value = prepared.value
  if (!readable.value || !value || !key || !linkPlan.value?.links.some(link => link.key === key)
    || value.sessionEpoch !== store.sessionEpoch || value.accessEpoch !== currentTrainingAccessEpoch()
    || item.status !== 'ACTIVE' || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(item.id)) return
  targets.value = { ...targets.value, [key]: { id: item.id, title: item.title } }
  choosing.value = undefined
}
function apply(transformed = false) {
  visibilityChanged(); elementVisibilityChanged()
  const value = prepared.value
  if (!readable.value || reading.value || !value) return
  if (value.sessionEpoch !== store.sessionEpoch || value.accessEpoch !== currentTrainingAccessEpoch()) {
    clear(); cleared.value = true; return
  }
  const result = processed.value
  if (transformed && (!result || result.error || choosing.value)) return
  emit('apply', { fileName: value.fileName, source: value.source, sourceSha256: value.sourceSha256,
    title: transformed ? result!.title : value.title,
    body: transformed ? result!.body : value.body,
    ...(transformed && useMetadataTags.value ? { tags: [...result!.tags] } : {}) })
}
</script>

<template>
  <section ref="element" class="knowledge-text-import" aria-label="单份知识文本导入">
    <p class="notice">选择和预览只在浏览器内存进行，不会立即创建知识或发送模型。确认填入后仍需点击“创建并保存”，才向本系统发送原始文本及最终正文，用于服务器计算指纹、创建笔记及登记导入；原文件不作为附件上传。</p>
    <p class="field-help">导入创建新笔记及首版，不按同名标题覆盖；已登记的相同原始文本默认拒绝重复创建，可在表单明确选择新副本。文件名建议为标题，可在填入后修改。支持严格UTF-8，单份最多400000字节、正文最多100000字符，与编辑器计数一致；不截断，BOM和换行原样保留。</p>
    <label class="file-field">选择 Markdown／TXT 文件<input type="file" accept=".md,.markdown,.txt" :disabled="!readable || reading" @change="chooseFile" /></label>
    <p v-if="reading" role="status">正在本地读取并核对文件…</p>
    <p v-if="cleared" class="notice" role="status">读取条件或可见性已变化，本地文件预览已清除，请重新选择文件。已有创建表单输入仍保留。</p>
    <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
    <template v-if="prepared && readable">
      <strong>{{ prepared.fileName }} · {{ prepared.byteSize }}字节 · {{ prepared.body.length }}字符</strong>
      <p class="field-help">建议标题：{{ prepared.title }}</p>
      <p class="notice">默认保留全部原文和文件名建议标题。可主动预览YAML、选择标题或移除元数据块，也可人工映射链接，均须核对最终全文再填入；不恢复旧ID、版本、已审状态、许可、附件或布局。原站内 knowledge: 链接在创建时仍按既有规则核对本人知识，缺失／无权目标会拒绝保存。TXT导入后也按知识Markdown正文阅读。</p>
      <pre tabindex="0" aria-label="待填入的完整文件原文">{{ prepared.body }}</pre>
      <NButton :disabled="!readable || reading" @click="inspectMetadata">{{ metadata ? '重新解析YAML并清除元数据选项' : '预览YAML属性（可选）' }}</NButton>
      <template v-if="metadata">
        <p v-if="metadata.problem" class="notice" role="status">{{ metadata.problem }}</p>
        <template v-else>
          <p class="field-help">仅解析开头元数据块，最多16384字符、64个属性、解析深度上限8及512次节点解析；不推断日期／数字，不支持锚点或自定义类型。title与tags／tag可分别明确采用，标签不按逗号、空格或#拆分。导入创建笔记，概念别名仅预览；其余属性不作为系统身份、状态或许可。</p>
          <p v-if="!metadata.fields.length" class="field-help">元数据块为空。</p>
          <dl class="metadata-fields"><div v-for="field in metadata.fields" :key="field.key"><dt>{{ field.key }}</dt><dd>{{ field.state === 'TITLE' ? '可选标题' : field.state === 'DISPLAY' ? (field.key === 'tags' || field.key === 'tag' ? '可选标签，需整体通过下方限制' : '概念别名仅预览，不写入笔记') : field.state === 'UNSUPPORTED' ? '该属性形状不支持，仅保留原文' : '未采用属性，仅保留原文' }}<ul v-if="field.values"><li v-for="(value, index) in field.values" :key="index"><code>「{{ value }}」</code></li></ul></dd></div></dl>
          <NCheckbox v-model:checked="useMetadataTitle" :disabled="!readable || !metadata.title">采用YAML title作为新笔记标题</NCheckbox>
          <p v-if="tagSelection.problem" class="field-help">{{ tagSelection.problem }}原标题、链接与原文处理仍可使用。</p>
          <NCheckbox v-model:checked="useMetadataTags" :disabled="!readable || !!tagSelection.problem">明确采用tags／tag作为新笔记标签（创建时去首尾空白、忽略大小写去重，保留首项写法）</NCheckbox>
          <NCheckbox v-model:checked="removeMetadata" :disabled="!readable">从待创建正文移除整个元数据块</NCheckbox>
          <p class="notice">{{ removeMetadata ? '已选择移除整块：上列所有属性都会从新笔记正文移除，只有明确选中的title和标签进入创建表单；别名及其他未采用属性不会另外保存。请核对全文和所选标签。' : '未选择移除，整块元数据仍在新笔记正文中；标签采用与移除独立，解析不会修改原文件。' }}</p>
        </template>
      </template>
      <NButton :disabled="!readable || reading" @click="inspectLinks">{{ linkPlan ? '重新解析链接并清除本次映射' : '解析可映射链接（可选）' }}</NButton>
      <template v-if="linkPlan">
        <p class="field-help">普通Wiki双链（可带显示名）、无锚点的 .md／.markdown／.txt 相对链接及旧 knowledge: 地址可逐组人工映射。代码、图片／嵌入、元数据、引用式链接、锚点／块引用和未知语法保留原文；不读取关联文件或远程资源，不按标题或路径自动匹配。相同类型及原地址的一组链接一同改写。</p>
        <p v-if="linkPlan.problem" class="notice error" role="alert">{{ linkPlan.problem }}</p>
        <p v-else-if="!linkPlan.links.length" class="field-help" role="status">没有识别到本批支持的可映射链接，全文仍保留。</p>
        <div v-for="link in linkPlan.links" :key="link.key" class="link-mapping">
          <strong>{{ link.kind === 'WIKI' ? 'Wiki双链' : link.kind === 'RELATIVE' ? '相对笔记链接' : '原站内地址' }}：{{ link.target }}</strong>
          <p class="field-help">{{ link.occurrences.length }}处；{{ targets[link.key] ? '已选择映射' : '保留原文' }}</p>
          <details><summary>查看本组原文片段</summary><pre v-for="occurrence in link.occurrences" :key="occurrence.from">{{ occurrence.original }}</pre></details>
          <p v-if="targets[link.key]">目录选择：{{ targets[link.key]!.title }} · {{ targets[link.key]!.id }}</p>
          <div class="row-actions"><NButton size="small" :disabled="!readable" @click="choosing = choosing === link.key ? undefined : link.key">{{ choosing === link.key ? '收起选择器' : targets[link.key] ? '更换目标知识' : '选择已有知识' }}</NButton><NButton v-if="targets[link.key]" size="small" :disabled="!readable" @click="delete targets[link.key]">恢复本组原文</NButton></div>
          <KnowledgePicker v-if="choosing === link.key && readable" :key="link.key" :disabled="!readable" @choose="chooseTarget" />
        </div>
        <p v-if="linkPlan.links.length" class="notice">只有打开选择器才查询现有知识目录。选择记录只是目录快照，最终创建由服务器核对本人目标并登记正文引用；这里不固定目标正文版本，不采用其内容、审阅身份或AI许可，也不生成语义关系。未选择和不支持的语法会继续保留，后续可能仍有断链。</p>
      </template>
      <template v-if="processed">
        <strong>处理结果 · {{ mappedCount }}处链接 · {{ processed.body.length }}字符</strong>
        <p>将填入的标题：{{ processed.title }}</p>
        <p class="field-help">标题{{ useMetadataTitle ? '采用YAML title' : '使用文件名建议' }}；元数据块{{ removeMetadata ? '移除' : '保留' }}；{{ mappedCount }}处链接按所选目标改写。</p>
        <p class="field-help">标签{{ useMetadataTags ? '明确采用下列原始声明，创建时按整理规则规范化' : '未采用，不另外保存' }}。</p>
        <ul v-if="useMetadataTags"><li v-for="(tag, index) in processed.tags" :key="index"><code>「{{ tag }}」</code></li></ul>
        <p v-if="processed.error" class="notice error" role="alert">{{ processed.error }}</p>
        <pre v-else tabindex="0" aria-label="元数据与链接处理后的完整待填入正文">{{ processed.body }}</pre>
        <NButton type="primary" :disabled="!readable || reading || !!processed.error || !!choosing" @click="apply(true)">确认处理后的标题、全文与所选标签并填入新笔记表单</NButton>
      </template>
      <NButton :type="processed ? 'default' : 'primary'" :disabled="!readable || reading" @click="apply(false)">仅用文件名标题与完整原文填入新笔记表单</NButton>
    </template>
    <NButton :disabled="!readable" @click="emit('cancel')">取消文件导入，保留表单输入</NButton>
  </section>
</template>

<style scoped>
.knowledge-text-import { display: grid; justify-items: start; gap: 12px; min-width: 0; }
p { margin: 0; }
strong { overflow-wrap: anywhere; }
.file-field { display: grid; gap: 7px; min-width: 0; max-width: 100%; }
input { max-width: 100%; }
.metadata-fields { display: grid; gap: 10px; margin: 0; min-width: 0; overflow-wrap: anywhere; }
.metadata-fields dt { font-weight: 600; }
.metadata-fields dd { margin: 4px 0 0; }
.metadata-fields ul { margin: 5px 0 0; padding-left: 20px; }
.link-mapping { display: grid; gap: 8px; box-sizing: border-box; width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); overflow-wrap: anywhere; }
.link-mapping :deep(.knowledge-picker) { min-width: 0; }
.link-mapping details { min-width: 0; }
.link-mapping pre { margin-top: 8px; }
pre { box-sizing: border-box; width: 100%; max-height: 280px; overflow: auto; margin: 0; padding: 12px; border: 1px solid var(--line); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
</style>
