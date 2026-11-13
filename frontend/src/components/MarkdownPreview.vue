<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
const props = defineProps<{ value: string }>()
const router = useRouter()
const route = useRoute()
const markdown = new MarkdownIt({ html: false, linkify: false, breaks: false })
function internalId(destination: string, scheme: 'knowledge' | 'material') {
  const normalized = destination.replace(new RegExp(`^${scheme}:`, 'i'), `${scheme}:`)
  return new RegExp(`^${scheme}:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$`).exec(normalized)?.[1]
}
// 资料正文不能通过远程图片自动发出请求；外链需用户主动打开。
markdown.renderer.rules.image = (tokens, index) => {
  const token = tokens[index]
  const label = markdown.utils.escapeHtml(token?.content || '私有附件')
  const material = internalId(String(token?.attrGet('src') ?? ''), 'material')
  if (material) {
    // 图片可位于另一个 Markdown 链接内，使用可键盘操作的独立目标，避免嵌套 a 标签。
    return `<span class="blocked-image" role="link" tabindex="0" data-material-id="${material}">附件图片：${label}（点击查看私有资料）</span>`
  }
  return `<span class="blocked-image">图片：${label}（暂不加载）</span>`
}
const renderLink = markdown.renderer.rules.link_open ?? ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options))
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  const href = String(tokens[index]?.attrGet('href') ?? '')
  const knowledge = internalId(href, 'knowledge')
  const material = internalId(href, 'material')
  if (knowledge) {
    tokens[index]?.attrSet('href', `/knowledge?item=${knowledge}`)
    tokens[index]?.attrSet('data-knowledge-id', knowledge)
  } else if (material) {
    tokens[index]?.attrSet('href', `/materials?attachment=${material}`)
    tokens[index]?.attrSet('data-material-id', material)
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
function navigate(event: MouseEvent | KeyboardEvent) {
  if (event instanceof KeyboardEvent && event.key !== 'Enter' && event.key !== ' ') return
  const target = event.target instanceof Element ? event.target : null
  const anchor = target?.closest<HTMLElement>('[data-material-id], a[data-knowledge-id]')
  if (!anchor || !event.currentTarget || !(event.currentTarget as Element).contains(anchor)) return
  event.preventDefault()
  // 内部引用走路由守卫；原文中的协议绝不直接交给浏览器或绕过未保存保护。
  if (anchor.dataset.materialId) {
    void router.push({ name: 'materials', query: { attachment: anchor.dataset.materialId } })
    return
  }
  void router.push({ name: 'knowledge', query: { ...(route.name === 'knowledge' ? route.query : {}), item: anchor.dataset.knowledgeId, version: undefined, lineStart: undefined, lineEnd: undefined } })
}
</script>
<template><div v-if="value" class="markdown-preview" @click="navigate" @keydown="navigate" v-html="rendered" /><p v-else class="empty-inline">这里还没有正文。</p></template>
<style scoped>
.markdown-preview :deep([data-material-id]) { cursor: pointer; color: var(--brand); text-decoration: underline; text-underline-offset: 3px; }
</style>
