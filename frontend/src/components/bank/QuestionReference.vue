<script setup lang="ts">
import type { QuestionReference } from '../../api/bank'
import MarkdownPreview from '../MarkdownPreview.vue'
import StandardReviewWorkspace from './StandardReviewWorkspace.vue'
defineProps<{ reference: QuestionReference; disabled?: boolean }>()
</script>

<template>
  <section class="question-reference" aria-label="已显式打开的个人参考">
    <header><h4>个人参考／未审</h4><p class="field-help">本次查看已记录于 {{ new Date(reference.recordedAt).toLocaleString('zh-CN') }}，用于保留接触背景，不代表已阅读或掌握。</p></header>
    <MarkdownPreview v-if="reference.referenceAnswer" :value="reference.referenceAnswer" />
    <p v-else class="field-help">这个版本没有填写参考正文。</p>
    <div v-if="reference.checkpoints.length"><h4>参考检查点</h4><ol><li v-for="point in reference.checkpoints" :key="point.id">{{ point.text }}</li></ol></div>
    <p v-else class="field-help">这个版本没有填写检查点。</p>
    <StandardReviewWorkspace :key="`${reference.questionId}:${reference.versionId}`" :reference="reference" :disabled="disabled" />
  </section>
</template>

<style scoped>
.question-reference { display: grid; gap: 15px; margin-top: 20px; padding-top: 18px; border-top: 1px solid var(--line); }
h4 { font-size: 14px; } header p { margin-top: 7px; }
ol { margin: 10px 0 0; padding-left: 23px; } li { padding: 4px 0; white-space: pre-wrap; overflow-wrap: anywhere; max-width: 75ch; }
</style>
