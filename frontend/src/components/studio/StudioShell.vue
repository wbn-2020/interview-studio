<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NDropdown, NDrawer, NDrawerContent } from 'naive-ui'
import RBrand from '../../replica/RBrand.vue'
import RIcon from '../../replica/RIcon.vue'
import { primarySections, utilitySections, sectionFor, tabsFor } from '../../navigation/sections'
import { pendingNavigation, navigationError } from '../../state/navigation'
import { useRouter } from 'vue-router'
const props = defineProps<{ username?: string | null; unsaved: number; unresolved: number; busy: boolean }>()
const emit = defineEmits<{ command: []; signout: [] }>()
const route = useRoute(), router = useRouter()
const collapsed = ref(false), mobileMenu = ref(false)
const section = computed(() => sectionFor(route))
const sectionTabs = computed(() => tabsFor(route))
const currentTab = computed(() => sectionTabs.value.find(item => item.active))
const sectionOptions = computed(() => sectionTabs.value.map((item, index) => ({ label: item.label, key: String(index) })))
const avatarLabel = computed(() => Array.from(props.username?.trim() || '我')[0]!.toLocaleUpperCase())
const saveLabel = computed(() => props.unresolved ? '保存待确认' : props.unsaved ? `${props.unsaved} 处未保存` : '更改已保存')
const accountOptions = computed(() => [
  { label: props.username || '当前账户', key: 'account', disabled: true },
  { label: '个人设置', key: 'settings' },
  { label: '数据管理', key: 'data-management' },
  { type: 'divider', key: 'divider' },
  { label: '退出登录', key: 'signout', disabled: props.busy },
])
function accountAction(key: string) {
  if (key === 'signout') emit('signout')
  else void router.push({ name: key })
}
function sectionAction(key: string) {
  const tab = sectionTabs.value[Number(key)]
  if (tab) void router.push(tab.to)
}
watch(() => route.fullPath, () => { mobileMenu.value = false })
</script>

<template>
  <div class="app-shell studio-product" :class="{ 'sidebar-collapsed': collapsed }">
    <a class="studio-skip-link" href="#main-content">跳到工作区</a>
    <aside class="app-nav studio-sidebar" aria-label="工作台导航">
      <RouterLink class="studio-brand" to="/today" aria-label="问阶 · 今日"><RBrand :variant="2" /></RouterLink>
      <nav class="studio-primary-nav" aria-label="主导航">
        <RouterLink v-for="item in primarySections" :key="item.key" :to="item.to" class="studio-nav-item" :class="{ 'studio-selected': section.key === item.key }" :aria-current="section.key === item.key ? 'page' : undefined" :title="item.label"><RIcon :name="item.icon" :size="20" /><span>{{ item.label }}</span></RouterLink>
      </nav>
      <nav class="studio-utility-nav" aria-label="工作台工具">
        <span class="studio-nav-caption">工作台工具</span>
        <RouterLink v-for="item in utilitySections" :key="item.key" :to="item.to" class="studio-nav-item" :class="{ 'studio-selected': section.key === item.key }" :aria-current="section.key === item.key ? 'page' : undefined" :title="item.label"><RIcon :name="item.icon" :size="20" /><span>{{ item.label }}</span></RouterLink>
      </nav>
      <div class="studio-sidebar-footer"><span>准备 · 练习 · 积累</span><button class="studio-icon-button" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" :title="collapsed ? '展开侧栏' : '收起侧栏'" :aria-expanded="!collapsed" @click="collapsed = !collapsed"><RIcon name="panel-left" :size="18" /></button></div>
    </aside>
    <header class="studio-topbar">
      <button class="studio-icon-button studio-menu-trigger" aria-label="打开导航菜单" :aria-expanded="mobileMenu" @click="mobileMenu = true"><RIcon name="panel-left" :size="21" /></button>
      <NDropdown v-if="sectionTabs.length" trigger="click" :options="sectionOptions" @select="sectionAction">
        <button class="studio-section-switcher" aria-haspopup="menu" :aria-label="`${section.label}：${currentTab?.label ?? '工作台'}`"><span>{{ section.label }}</span><template v-if="currentTab && currentTab.label !== section.label"><RIcon name="chevron-right" :size="14" /><strong>{{ currentTab.label }}</strong></template><RIcon name="chevron-down" :size="14" /></button>
      </NDropdown>
      <span v-else class="studio-breadcrumb">{{ section.label }}</span>
      <button class="studio-global-search" aria-haspopup="dialog" @click="emit('command')"><RIcon name="search" :size="18" /><span>搜索知识、题目与记录</span><kbd>Ctrl K</kbd></button>
      <div class="studio-top-status"><span class="studio-saved" :class="{ pending: unsaved || unresolved }" :title="saveLabel" :aria-label="saveLabel" role="status"><RIcon :name="unsaved || unresolved ? 'clock' : 'check'" :size="16" /><span class="studio-saved-label">{{ saveLabel }}</span></span><NDropdown trigger="click" :options="accountOptions" @select="accountAction"><button class="studio-avatar" title="账户与设置" aria-label="账户与设置" aria-haspopup="menu">{{ avatarLabel }}</button></NDropdown></div>
      <div v-if="pendingNavigation" class="studio-route-progress" role="status"><span class="sr-only">正在打开页面…</span></div>
    </header>
    <div class="studio-content-frame">
      <main id="main-content" class="app-main studio-main" tabindex="-1">
        <p v-if="navigationError" class="notice error" role="alert">{{ navigationError }}</p>
        <slot />
      </main>
    </div>
    <nav class="studio-mobile-nav" aria-label="移动端主导航"><RouterLink v-for="item in primarySections" :key="item.key" :to="item.to" :class="{ selected: section.key === item.key }" :aria-current="section.key === item.key ? 'page' : undefined"><RIcon :name="item.icon" :size="20" /><span>{{ item.label }}</span></RouterLink></nav>
    <NDrawer v-model:show="mobileMenu" placement="left" :width="280"><NDrawerContent title="问阶 · 工作台" closable><nav class="studio-drawer-nav" aria-label="全部导航"><RouterLink v-for="item in [...primarySections, ...utilitySections]" :key="item.key" :to="item.to" :aria-current="section.key === item.key ? 'page' : undefined"><RIcon :name="item.icon" :size="20" />{{ item.label }}</RouterLink></nav><section v-if="sectionTabs.length" class="studio-drawer-section" :aria-label="`${section.label}功能`"><p>{{ section.label}}功能</p><RouterLink v-for="tab in sectionTabs" :key="tab.label" :to="tab.to" :aria-current="tab.active ? 'page' : undefined" @click="mobileMenu = false">{{ tab.label }}</RouterLink></section></NDrawerContent></NDrawer>
  </div>
</template>
