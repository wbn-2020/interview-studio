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
  <!-- 弹层根节点保留 div，确保会话过期时的焦点保护不会把表单判为边界外。 -->
  <div class="studio-login" role="dialog" aria-modal="true" aria-labelledby="login-title">
    <div class="login-dawn" aria-hidden="true">
      <div class="login-beam" />
      <svg class="login-horizon" viewBox="0 0 1536 1024" fill="none">
        <defs>
          <radialGradient id="studio-planet-surface" cx=".36" cy="0" r=".7">
            <stop stop-color="#282016" /><stop offset=".36" stop-color="#100e0b" /><stop offset="1" stop-color="#080807" />
          </radialGradient>
          <linearGradient id="studio-horizon-edge" x1="0" y1="420" x2="850" y2="540" gradientUnits="userSpaceOnUse">
            <stop stop-color="#ac7645" stop-opacity=".55" /><stop offset=".63" stop-color="#f2c494" /><stop offset="1" stop-color="#c69b6a" stop-opacity=".55" />
          </linearGradient>
          <linearGradient id="studio-horizon-fade" x2="0" y1="0" y2="1024" gradientUnits="userSpaceOnUse">
            <stop offset=".65" stop-color="white" /><stop offset=".87" stop-color="black" />
          </linearGradient>
          <mask id="studio-horizon-mask"><path fill="url(#studio-horizon-fade)" d="M0 0h1536v1024H0Z" /></mask>
          <filter id="studio-horizon-glow" x="-20%" y="-100%" width="140%" height="300%"><feGaussianBlur stdDeviation="14" /></filter>
        </defs>
        <g mask="url(#studio-horizon-mask)">
          <path d="M-20 485C280 366 535 402 850 548L1536 1024H-20Z" fill="url(#studio-planet-surface)" />
          <path d="M-20 485C280 366 535 402 850 548" stroke="#e5ac6d" stroke-width="8" opacity=".55" filter="url(#studio-horizon-glow)" />
          <path d="M-20 485C280 366 535 402 850 548" stroke="url(#studio-horizon-edge)" stroke-width="1.3" />
        </g>
      </svg>
    </div>
    <div class="login-introduction">
      <RBrand large :variant="1" />
      <p class="login-description">知识、准备、训练与复盘，留在一个工作台。</p>
      <ul class="login-features"><li v-for="feature in features" :key="feature.title"><RIcon :name="feature.icon" variant="login" :size="49" :stroke-width="1.5" :style="{ color: feature.color }" /><div><h2>{{ feature.title }}</h2><p>{{ feature.detail }}</p></div></li></ul>
      <p class="login-deployment"><i /><RIcon name="shield-check" :size="28" /><span>个人部署 · 数据由你掌控</span><i /></p>
    </div>
    <form class="studio-login-form" @submit.prevent="emit('submit')">
      <h1 id="login-title">登录</h1><p class="login-subtitle">进入你的个人空间</p>
      <p v-if="error" class="login-error" role="alert">{{ error }}</p><p v-else-if="loading" class="login-error" role="status">正在确认会话…</p>
      <label class="login-field login-name" for="username"><RIcon name="user" variant="login" :size="37" :stroke-width="1.5" /><span class="sr-only">登录名</span><input id="username" :value="username" :disabled="busy || loading" name="username" autocomplete="username" placeholder="登录名" required :spellcheck="false" @input="emit('update:username', ($event.target as HTMLInputElement).value)"></label>
      <label class="login-field login-password" for="password"><RIcon name="lock" variant="login" :size="36" :stroke-width="1.5" /><span class="sr-only">密码</span><input id="password" :value="password" :type="passwordVisible ? 'text' : 'password'" :disabled="busy || loading" name="password" autocomplete="current-password" placeholder="密码" required @input="emit('update:password', ($event.target as HTMLInputElement).value)"><button type="button" class="password-toggle" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" @click="passwordVisible = !passwordVisible"><RIcon :name="passwordVisible ? 'eye' : 'eye-off'" variant="login" :size="29" /></button></label>
      <button type="submit" class="login-enter" :disabled="busy || loading" :aria-busy="busy">{{ busy ? '正在登录…' : '进入工作台' }}</button>
      <button type="button" class="login-reconnect" :disabled="busy || loading" @click="emit('reconnect')">重新检查连接</button>
      <p class="login-account-note">账号由服务器管理员配置</p>
    </form>
  </div>
</template>
<style scoped>
.studio-login { position: relative; width: 100vw; min-height: 100dvh; overflow: hidden; color: #f4eee4; background: radial-gradient(ellipse at 39% 37%, #4b342025, transparent 43%), #0a0907; font: 20px/1.5 "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif; will-change: transform; }
/* 输入框可以收缩，表单图标仍须保留参考尺寸。 */
.studio-login :deep(.r-icon) { flex-shrink: 0; display: inline-block; vertical-align: middle; }
.login-dawn { position: absolute; inset: 0; pointer-events: none; overflow: hidden; background: radial-gradient(ellipse 360px 190px at 35% 42%, #e3ac6675, #a16c3333 45%, transparent 77%), radial-gradient(ellipse 600px 370px at 32% 36%, #8b603340, transparent 75%); }
.login-beam { position: absolute; inset: 0; clip-path: polygon(0 0, 58.3% 0, 34.6% 42.3%, 0 48%); background: linear-gradient(131deg, #24190f00 12%, #7d542c18 25%, #b17d4752 46%, #e5ad6a6e 65%); }
.login-beam::after { content: ""; position: absolute; left: 531px; top: -145px; width: 1px; height: 574px; background: linear-gradient(0deg, #ebbb7ca3, #95602f44 65%, transparent); transform: rotate(40.2deg); transform-origin: bottom; box-shadow: 0 0 22px 4px #bc834b21; }
.login-horizon { position: absolute; inset: 0; width: 1536px; height: 1024px; }
.login-introduction { position: absolute; inset: 0; }
.login-introduction :deep(.r-brand) { position: absolute; left: 105px; top: 179px; display: flex; gap: 26px; align-items: center; white-space: nowrap; }
.login-introduction :deep(.r-emblem) { width: 150px; height: 145px; color: #dba064; filter: none; }
.login-introduction :deep(.r-brand-word) { transform: translateY(-6px); font-size: 56px; font-weight: 650; line-height: 1.18; }
.login-introduction :deep(.r-brand-word small) { display: block; margin-top: 7px; font-size: 35px; font-weight: 400; color: #eee5d8; }
.login-description { position: absolute; left: 105px; top: 345px; font-size: 32.5px; font-weight: 500; line-height: 1.5; white-space: nowrap; color: #f7e8cf; }
.login-features { position: absolute; top: 463px; left: 105px; width: 513px; display: flex; flex-direction: column; gap: 4px; list-style: none; padding: 0; margin: 0; }
.login-features li { display: flex; align-items: center; gap: 40px; height: 89px; padding: 0 27px; border-radius: 25px; background: linear-gradient(110deg, #28231b9e, #211d164d); }
.login-features h2 { font-size: 25px; line-height: 1.45; font-weight: 500; color: #f6f0e5; }.login-features p { color: #d1cbc1; font-size: 21px; line-height: 1.5; }
.login-deployment { position: absolute; top: 866px; left: 105px; width: 556px; display: flex; align-items: center; justify-content: center; gap: 14px; color: #938b80; font-size: 20px; white-space: nowrap; }.login-deployment i { flex: 1; height: 1px; background: #6c6253; }
.login-deployment :deep(.r-icon) { margin-left: 4px; }
.studio-login-form { position: absolute; left: 54.5573%; top: 139px; width: 40.8203125%; height: 745px; border: 2px solid transparent; border-radius: 38px; background: linear-gradient(120deg, #2a241c, #11100e 55%, #211c16) padding-box, linear-gradient(135deg, #a29a8d, #4c463d 27%, #2d2924 69%, #373029) border-box; }
.studio-login-form h1 { position: absolute; left: 53px; top: 35px; font-size: 56px; line-height: 1.4; font-weight: 600; color: #fff4e1; letter-spacing: 0; }.login-subtitle { position: absolute; top: 117px; left: 53px; font-size: 27px; color: #b2aa9e; }
.login-error { position: absolute; left: 53px; right: 45px; top: 166px; color: #eda98b; font-size: 13px; line-height: 1.4; max-height: 35px; overflow-y: auto; }
.login-field { position: absolute; left: 48px; right: 48px; height: 85px; display: flex; align-items: center; gap: 24px; padding: 0 26px; border: 2px solid transparent; border-radius: 44px; background: linear-gradient(110deg, #211d17, #151412) padding-box, linear-gradient(120deg, #72695c, #35312b 75%) border-box; color: #e3d9c8; }.login-name { top: 205px; }.login-password { top: 319px; }
.login-field input { min-width: 0; width: 100%; height: 100%; padding: 0; border: 0; background: transparent; outline: none; font-size: 27px; color: inherit; }.login-field input::placeholder { color: #78746c; opacity: 1; }.login-field:focus-within { outline: 1px solid #d2a36e; outline-offset: 3px; }.login-field input:focus-visible { outline: none; }
.password-toggle { display: grid; place-items: center; flex: none; width: 34px; height: 40px; color: #6d6a64; padding: 0; }
.login-enter, .login-reconnect { position: absolute; left: 48px; right: 48px; height: 76px; border-radius: 40px; font-size: 27px; transform: translateY(-1px); }
.login-enter { top: 446px; background: linear-gradient(170deg, #dca368, #cb8d54 53%, #b37b48); border: 1px solid #e6b17c; box-shadow: inset 0 1px 1px #f8d0a7a6; color: #100a04; font-weight: 650; }
.login-reconnect { top: 540px; border: 2px solid transparent; background: linear-gradient(120deg, #191713, #1b1712) padding-box, linear-gradient(160deg, #6e665b, #39342d 75%) border-box; color: #f0e7d7; }
.login-account-note { position: absolute; top: 645px; left: 48px; right: 48px; padding-top: 22px; border-top: 1px solid #49403466; color: #938b80; text-align: center; font-size: 20px; }
/* 基准视口使用整数宽度，避免构建后的百分比舍入改变表单边缘。 */
@media (width: 1536px) { .studio-login-form { width: 627px; } }
@media (min-width: 1101px) and (min-height: 1025px) { .studio-login-form { top: calc(50dvh - 373px); } }
@media (max-width: 1100px) { .login-introduction { display: none; }.studio-login { display: grid; place-items: center; padding: 30px 20px; }.studio-login-form { position: relative; left: auto; top: auto; width: min(627px, 100%); }.login-dawn { opacity: .6; } }
@media (max-width: 600px) { .studio-login-form { height: 650px; border-radius: 28px; }.studio-login-form h1 { left: 28px; font-size: 42px; }.login-subtitle { top: 107px; left: 28px; font-size: 23px; }.login-field, .login-enter, .login-reconnect, .login-account-note { left: 24px; right: 24px; }.login-name { top: 181px; }.login-password { top: 280px; }.login-field { height: 73px; gap: 16px; padding: 0 20px; }.login-field input, .login-enter, .login-reconnect { font-size: 22px; }.login-enter { top: 391px; height: 65px; }.login-reconnect { top: 475px; height: 65px; }.login-account-note { top: 566px; font-size: 16px; }.login-error { top: 147px; left: 28px; } }
</style>
