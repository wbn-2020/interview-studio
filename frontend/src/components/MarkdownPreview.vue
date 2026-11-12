<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
const props = defineProps<{ value: string }>()
const router = useRouter()
const route = useRoute()
const markdown = new MarkdownIt({ html: false, linkify: false, breaks: false })
// 资料正文不能通过远程图片自动发出请求；外链需用户主动打开。
markdown.renderer.rules.image = (tokens, index) => `<span class="blocked-image">图片：${markdown.utils.escapeHtml(tokens[index]?.content ?? '')}（暂不加载）</span>`
const renderLink = markdown.renderer.rules.link_open ?? ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options))
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  const href = String(tokens[index]?.attrGet('href') ?? '')
  const normalizedScheme = href.replace(/^knowledge:/i, 'knowledge:')
  const knowledge = /^knowledge:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/.exec(normalizedScheme)
  if (knowledge) {
    tokens[index]?.attrSet('href', `/knowledge?item=${knowledge[1]}`)
    tokens[index]?.attrSet('data-knowledge-id', knowledge[1]!)
  } else {
    if (!/^(https?:\/\/|mailto:)/i.test(href)) tokens[index]?.attrSet('href', '#')
    tokens[index]?.attrSet('target', '_blank')
    tokens[index]?.attrSet('rel', 'noopener noreferrer')
  }
  return renderLink(tokens, index, options, env, self)
}
const rendered = computed(() => DOMPurify.sanitize(markdown.render(props.value), {
  ADD_ATTR: ['target'], FORBID_TAGS: ['img', 'iframe', 'form', 'input', 'video', 'audio', 'object'],
}))
function navigate(event: MouseEvent) {
  const anchor = (event.target as Element).closest<HTMLAnchorElement>('a[data-knowledge-id]')
  if (!anchor) return
  event.preventDefault()
  // 内部引用走路由守卫；原文中的协议绝不直接交给浏览器或绕过未保存保护。
  void router.push({ name: 'knowledge', query: { ...(route.name === 'knowledge' ? route.query : {}), item: anchor.dataset.knowledgeId, version: undefined, lineStart: undefined, lineEnd: undefined } })
}
</script>
<template><div v-if="value" class="markdown-preview" @click="navigate" v-html="rendered" /><p v-else class="empty-inline">这里还没有正文。</p></template>
