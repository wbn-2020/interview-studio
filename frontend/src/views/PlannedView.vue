<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()
const props = defineProps<{ section?: string }>()
const modules: Record<string, { title: string; text: string; stage: string }> = {
  today: { title: '今日', text: '从今日安排、到期回忆和待复练事项中，找到下一步。', stage: '回忆与成长阶段' },
  practice: { title: '文字训练', text: '在固定的问题条件下，完成独立文字作答与系统设计演练。', stage: 'S4 训练会话与独立作答' },
  review: { title: '复盘', text: '回到回答原文，形成改进动作，再用一次独立作答验证。', stage: '复盘与复练阶段' },
  growth: { title: '成长', text: '以回忆、回答和复练记录，呈现有证据支持的变化。', stage: '回忆与成长阶段' },
}
const page = computed(() => modules[props.section ?? String(route.params.section)]!)
const practice = computed(() => props.section === 'practice')
const today = computed(() => (props.section ?? route.params.section) === 'today')
</script>
<template>
  <header class="page-heading"><div><p class="section-label">问阶工作台</p><h1>{{ page.title }}</h1></div><span class="status-pill">尚未开放</span></header>
  <section class="planned-view"><h2>{{ page.text }}</h2><p>该入口将在「{{ page.stage }}」完成接入。</p><template v-if="practice"><p>目前可以整理题目与个人参考、编排题单和学习路线。训练会话、回答草稿、提交及暂停恢复尚未接入。</p><RouterLink class="text-link" to="/practice/questions">前往题库 →</RouterLink></template><template v-else-if="today"><p>可以先到准备任务中安排具体事项、记录状态和查看变更历史；今日聚合、到期回忆与待复练列表仍待后续接入。</p><RouterLink class="text-link" to="/prepare/tasks">前往准备任务 →</RouterLink></template><template v-else><p>目前可以先使用知识库，建立笔记、概念和真实关联。</p><RouterLink class="text-link" to="/knowledge">前往知识空间 →</RouterLink></template></section>
</template>
