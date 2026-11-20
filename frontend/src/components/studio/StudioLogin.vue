<script setup lang="ts">
import { ref } from 'vue'
import RBrand from '../../replica/RBrand.vue'
import RIcon from '../../replica/RIcon.vue'
defineProps<{ username: string; password: string; busy: boolean; loading: boolean; error: string }>()
const emit = defineEmits<{ 'update:username': [value: string]; 'update:password': [value: string]; submit: []; reconnect: [] }>()
const passwordVisible = ref(false)
const features = [
  { icon: 'book-open', title: '独立知识库', detail: '概念、笔记与出处', color: '#e99e62' },
  { icon: 'share-2', title: '知识星图', detail: '看见真实关联', color: '#91c5cf' },
  { icon: 'file-text', title: '文字训练', detail: '从空白回答到复盘', color: '#a2d3b5' },
  { icon: 'chart-no-axes-combined', title: '成长证据', detail: '有记录、也有待验证', color: '#ef8959' },
]
</script>
<template>
  <!-- 保留弹层根节点，确保会话过期后的焦点仍受登录边界保护。 -->
  <div class="studio-login" role="dialog" aria-modal="true" aria-labelledby="login-title">
    <div class="login-dawn" aria-hidden="true" />
    <div class="login-layout">
      <div class="login-introduction">
        <RBrand :variant="1" />
        <p class="login-description">知识、准备、训练与复盘，<br class="login-desktop-break">留在一个工作台。</p>
        <ul class="login-features">
          <li v-for="feature in features" :key="feature.title">
            <RIcon :name="feature.icon" :size="24" :stroke-width="1.5" :style="{ color: feature.color }" />
            <div><h2>{{ feature.title }}</h2><p>{{ feature.detail }}</p></div>
          </li>
        </ul>
        <p class="login-deployment"><RIcon name="shield-check" :size="17" /><span>个人部署 · 数据由你掌控</span></p>
      </div>
      <form class="studio-login-form" aria-describedby="login-subtitle" @submit.prevent="emit('submit')">
        <header><h1 id="login-title">登录</h1><p id="login-subtitle" class="login-subtitle">进入你的个人空间</p></header>
        <p v-if="error" id="login-feedback" class="login-error" role="alert">{{ error }}</p>
        <p v-else-if="loading" class="login-status" role="status">正在确认会话…</p>
        <div class="login-field-group">
          <label class="login-label" for="username">登录名</label>
          <div class="login-field login-name">
            <RIcon name="user" :size="21" :stroke-width="1.5" />
            <input id="username" :value="username" :disabled="busy || loading" name="username" autocomplete="username" autocapitalize="none" placeholder="输入登录名" required :spellcheck="false" @input="emit('update:username', ($event.target as HTMLInputElement).value)">
          </div>
        </div>
        <div class="login-field-group">
          <label class="login-label" for="password">密码</label>
          <div class="login-field login-password">
            <RIcon name="lock" :size="21" :stroke-width="1.5" />
            <input id="password" :value="password" :type="passwordVisible ? 'text' : 'password'" :disabled="busy || loading" name="password" autocomplete="current-password" placeholder="输入密码" required @input="emit('update:password', ($event.target as HTMLInputElement).value)">
            <button type="button" class="password-toggle" :disabled="busy || loading" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" @click="passwordVisible = !passwordVisible"><RIcon :name="passwordVisible ? 'eye' : 'eye-off'" :size="20" /></button>
          </div>
        </div>
        <button type="submit" class="login-enter" :disabled="busy || loading" :aria-busy="busy">{{ busy ? '正在登录…' : '进入工作台' }}</button>
        <button type="button" class="login-reconnect" :disabled="busy || loading" @click="emit('reconnect')"><RIcon name="refresh-cw" :size="16" />重新检查连接</button>
        <p class="login-account-note">账号由服务器管理员配置</p>
      </form>
    </div>
  </div>
</template>
<style scoped>
.studio-login {
  position: relative;
  isolation: isolate;
  display: grid;
  place-items: center;
  width: 100%;
  min-height: 100dvh;
  padding: 48px 32px;
  margin: 0;
  color: #f4eee4;
  background: #0b0a08;
  font: 16px/1.5 "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif;
}
.studio-login :deep(.r-icon) { flex: none; }
.login-dawn {
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background: radial-gradient(ellipse at 14% 26%, #9a602223, transparent 48%), radial-gradient(ellipse at 88% 90%, #5f41251a, transparent 45%);
}
/* 内容按可用宽度换列，短窗口由弹层自然滚动，保持表单及反馈完整。 */
.login-layout { display: grid; grid-template-columns: minmax(0, 1fr) 420px; align-items: center; gap: 88px; width: min(100%, 1040px); }
.login-introduction { min-width: 0; }
.login-introduction :deep(.r-brand) { display: flex; gap: 16px; align-items: center; }
.login-introduction :deep(.r-emblem) { width: 60px; height: 60px; flex: none; }
.login-introduction :deep(.r-brand-word) { font-size: 30px; font-weight: 650; line-height: 1.2; }
.login-introduction :deep(.r-brand-word small) { display: block; margin-top: 5px; font-size: 14px; font-weight: 400; color: #b9b0a3; }
.login-description { margin: 36px 0; font-size: 28px; line-height: 1.65; font-weight: 500; color: #f3e5d1; }
.login-features { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 28px 20px; list-style: none; padding: 0; margin: 0; }
.login-features li { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.login-features :deep(.r-icon) { margin-top: 3px; }
.login-features h2 { font-size: 16px; line-height: 1.5; font-weight: 550; }
.login-features p { margin-top: 4px; color: #afa79d; font-size: 13px; line-height: 1.65; }
.login-deployment { display: flex; align-items: center; gap: 8px; margin-top: 40px; color: #afa79d; font-size: 13px; }
.studio-login-form { display: grid; gap: 20px; min-width: 0; padding: 32px; border: 1px solid #40372d; border-radius: 16px; background: #1a1712; }
.studio-login-form h1 { font-size: 32px; line-height: 1.3; font-weight: 650; color: #fff4e1; letter-spacing: 0; }
.login-subtitle { margin-top: 8px; color: #b9b0a3; font-size: 15px; }
.login-error { padding: 10px 12px; border-left: 3px solid #e9a581; border-radius: 4px; background: #e9a5810a; color: #efb698; font-size: 14px; line-height: 1.6; overflow-wrap: anywhere; }
.login-status { color: #b9b0a3; font-size: 14px; }
.login-field-group { display: grid; gap: 8px; }
.login-label { font-size: 14px; color: #ded5c9; font-weight: 500; }
.login-field { display: flex; align-items: center; gap: 10px; min-height: 52px; padding: 0 12px; border: 1px solid #66594a; border-radius: 8px; background: #12110e; color: #b9b0a3; }
.login-field:focus-within { outline: 2px solid #dda16a; outline-offset: 2px; border-color: #dda16a; }
.login-field input { min-width: 0; width: 100%; height: 50px; padding: 0; border: 0; border-radius: 4px; background: transparent; outline: none; font-size: 16px; color: #f4eee4; caret-color: #f4eee4; }
.login-field input::placeholder { color: #a49c90; opacity: 1; }
.login-field input:focus-visible { outline: none; }
.login-field input:autofill, .login-field input:-webkit-autofill { -webkit-text-fill-color: #f4eee4; box-shadow: 0 0 0 1000px #12110e inset; caret-color: #f4eee4; }
.password-toggle { display: grid; place-items: center; flex: none; width: 44px; height: 44px; margin-right: -8px; padding: 0; color: #b9b0a3; border-radius: 6px; }
.password-toggle:hover:not(:disabled) { color: #f4eee4; background: #ffffff08; }
.login-enter { min-height: 50px; border-radius: 8px; background: #dda16a; color: #211307; font-size: 16px; font-weight: 650; }
.login-enter:hover:not(:disabled) { background: #edb77f; }
.login-enter:active:not(:disabled) { background: #cd905a; }
.login-reconnect { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 44px; margin-top: -12px; border-radius: 6px; color: #b9b0a3; font-size: 14px; }
.login-reconnect:hover:not(:disabled) { color: #f4eee4; background: #ffffff05; }
.login-account-note { margin-top: -4px; padding-top: 16px; border-top: 1px solid #3c3329; color: #afa79d; text-align: center; font-size: 13px; }
@media (max-width: 1100px) {
  .login-layout { gap: 40px; grid-template-columns: minmax(0, 1fr) 380px; }
  .login-description { font-size: 24px; }
  .login-features { grid-template-columns: 1fr; gap: 18px; }
}
@media (max-width: 820px) {
  .studio-login { padding: 28px 20px; }
  .login-layout { grid-template-columns: minmax(0, 1fr); gap: 24px; max-width: 420px; }
  .login-introduction :deep(.r-emblem) { width: 44px; height: 44px; }
  .login-introduction :deep(.r-brand-word) { font-size: 24px; }
  .login-introduction :deep(.r-brand-word small) { font-size: 12px; }
  .login-description { margin: 16px 0 0; font-size: 16px; line-height: 1.7; }
  .login-desktop-break, .login-features, .login-deployment { display: none; }
  .studio-login-form { padding: 24px; }
  .studio-login-form h1 { font-size: 28px; }
}
@media (max-width: 360px) {
  .studio-login { padding: 24px 16px; }
  .studio-login-form { padding: 20px; }
}
@media (forced-colors: active) {
  .login-field:focus-within { outline-color: Highlight; }
  .login-enter { border: 1px solid ButtonText; }
}
</style>
