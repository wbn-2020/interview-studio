<script setup lang="ts">
import { computed } from 'vue'
import type { RepracticeRecollectionBaseline } from '../../api/repractice'
import { repracticeBaselineLabels } from '../../api/repractice'
import { recollectionSourceLabels } from '../../api/recollection'
import { questionTypeLabels } from '../../api/bank'
import { reviewDate } from '../../api/review'

const props = defineProps<{ baseline: RepracticeRecollectionBaseline }>()
const content = computed(() => props.baseline.version.content)
</script>

<template>
  <section class="recollection-baseline" aria-label="固定的回忆基线">
    <header><p class="section-label">{{ repracticeBaselineLabels[content.baselineKind] }} · 固定基线</p><h3>{{ content.title }}</h3><p class="field-help">回忆 v{{ baseline.version.number }} · {{ recollectionSourceLabels[baseline.state] }}</p></header>
    <p class="notice">此处为用户事后补记。旧作答模式、计时、参考和辅助条件未知；回忆版本的保存时间不代表当时作答时间。</p>
    <dl><div><dt>问题类型</dt><dd>{{ questionTypeLabels[content.type] }}</dd></div><div><dt>问题回忆</dt><dd>{{ content.question }}</dd></div><div v-if="content.conditions"><dt>记得的条件</dt><dd>{{ content.conditions }}</dd></div><div><dt>不确定性说明</dt><dd>{{ content.uncertainty }}</dd></div></dl>
    <section class="fragment"><h4>{{ content.baselineKind === 'RECOLLECTION' ? '个人回忆的回答片段' : '没有旧回答' }}</h4><p v-if="content.baselineKind === 'RECOLLECTION'">{{ content.answerFragment }}</p><p v-else class="field-help">用户明确记录为无旧答。本次复练会形成一份新的正式提交，不能据此计算新旧提升。</p></section>
    <details v-if="content.feedback || content.gap"><summary>回忆的反馈与个人待补内容</summary><dl><div v-if="content.feedback"><dt>用户补记的反馈</dt><dd>{{ content.feedback }}</dd></div><div v-if="content.gap"><dt>个人认为的待补内容</dt><dd>{{ content.gap }}</dd></div></dl></details>
    <p class="field-help">版本保存于 {{ reviewDate(baseline.version.createdAt) }} · 基线采用的对象修订 {{ baseline.revision }}。后续补记不会替换这份固定内容。</p>
  </section>
</template>

<style scoped>
.recollection-baseline { display: grid; gap: 16px; min-width: 0; padding: 22px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
header { display: grid; gap: 8px; } h3, h4, p, dl, dd { margin: 0; } h3 { font-size: 18px; } h4 { font-size: 14px; }
dl, dl > div, .fragment { display: grid; gap: 10px; } dl { gap: 16px; } dt { font-size: 12px; color: var(--muted); }
dd, .fragment p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.85; font-size: 13px; }
.fragment { padding: 16px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 6px; }
summary { cursor: pointer; color: var(--muted); font-size: 13px; margin-bottom: 12px; } summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
@media (max-width: 650px) { .recollection-baseline { padding: 16px; } }
</style>
