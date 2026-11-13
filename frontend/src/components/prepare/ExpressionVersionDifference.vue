<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import RIcon from '../../replica/RIcon.vue'

const props = defineProps<{ before: string; after: string }>()
const page = ref(0)
const difference = computed(() => {
  const before = props.before.split(/\r\n|\r|\n/), after = props.after.split(/\r\n|\r|\n/)
  let prefix = 0, suffix = 0
  while (prefix < before.length && prefix < after.length && before[prefix] === after[prefix]) prefix++
  // 线性定位连续变化范围，避免长正文的平方级比较阻塞编辑器。
  while (suffix < before.length - prefix && suffix < after.length - prefix
    && before[before.length - suffix - 1] === after[after.length - suffix - 1]) suffix++
  return [
    ...before.slice(prefix, before.length - suffix).map(text => ({ kind: 'removed', text })),
    ...after.slice(prefix, after.length - suffix).map(text => ({ kind: 'added', text })),
  ]
})
const pageCount = computed(() => Math.max(1, Math.ceil(difference.value.length / 80)))
const visible = computed(() => difference.value.slice(page.value * 80, page.value * 80 + 80))
watch([() => props.before, () => props.after], () => { page.value = 0 })
</script>

<template>
  <div class="expression-version-difference">
    <p v-if="before === after" class="field-help">两版正文相同。</p>
    <p v-else-if="!difference.length" class="field-help">可见正文行相同，仅换行编码有变化。</p>
    <template v-else><div v-for="(line, index) in visible" :key="`${page}-${index}`" :class="['expression-difference-line', line.kind]"><span class="difference-sign" :aria-label="line.kind === 'removed' ? '旧版内容' : '新版内容'"><RIcon :name="line.kind === 'removed' ? 'minus' : 'plus'" :size="16" /></span><del v-if="line.kind === 'removed'">{{ line.text || '\u00a0' }}</del><ins v-else>{{ line.text || '\u00a0' }}</ins></div><footer><span>共同首尾之间的变化区段，可能仍含相同行。</span><nav v-if="pageCount > 1" aria-label="版本差异分页"><button :disabled="page === 0" @click="page--">上一页</button><span>{{ page + 1 }} / {{ pageCount }}</span><button :disabled="page + 1 >= pageCount" @click="page++">下一页</button></nav></footer></template>
  </div>
</template>

<style scoped>
.expression-version-difference { padding-top: 16px; }.expression-difference-line { display: flex; align-items: start; gap: 13px; margin: 7px 0; font-size: 21px; line-height: 1.8; }.difference-sign { display: grid; place-items: center; width: 23px; height: 23px; border: 1px solid currentColor; border-radius: 50%; margin-top: 7px; flex: none; }.removed { color: #d49b89; }.added { color: #b0d8b6; }.expression-difference-line :is(del,ins) { display: block; flex: 1; min-width: 0; white-space: pre-wrap; overflow-wrap: anywhere; padding: 2px 10px; border-radius: 7px; text-decoration: none; }.removed del { background: #8e4d3638; }.added ins { background: #325e393b; }footer { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 20px; color: var(--muted); font-size: 13px; }footer > span { flex: 1; }nav { display: flex; gap: 14px; align-items: center; }nav button { padding: 5px 9px; border: 1px solid var(--line); border-radius: 10px; }nav button:disabled { opacity: .4; }
@media(max-width:600px) { .expression-difference-line { font-size: 18px; } }
</style>
