<script setup lang="ts">
withDefaults(defineProps<{ large?: boolean; variant?: number }>(), { large: false, variant: 0 })
</script>
<template>
  <div class="r-brand" :class="{ 'r-brand-large': large }">
    <svg class="r-emblem" viewBox="0 0 100 100" fill="none" aria-hidden="true">
      <template v-if="variant === 10">
        <path d="M50 2 61 36 98 50 62 62 50 98 38 63 2 50 37 38Z" fill="currentColor" />
        <path d="m50 2 3 41 45 7-38 4-10 44-3-39L2 50l39-5Z" fill="#eee1c8" opacity=".32" />
        <path d="m50 39 10 11-10 11-10-11Z" fill="#544b3d" />
        <circle cx="32" cy="34" r="3" fill="#eee5d4" />
      </template>
      <template v-else-if="variant === 11">
        <path d="M50 1 61 35 94 50 62 61 50 99 39 64 4 51 38 39Z" fill="currentColor" />
        <path d="m50 1 1 45 43 4-40 4-4 45-2-44L4 51l40-4Z" fill="#f4c49a" />
        <path d="m20 20 27 18 32 43-25-19Z" fill="#dba078" />
        <path d="m76 20-15 28-39 31 18-28Z" fill="#c48c63" />
      </template>
      <template v-else-if="variant === 12">
        <path d="m50 1 10 35 35 14-34 11-11 38-11-38L4 50l34-12Z" fill="currentColor" />
        <path d="m18 18 28 20 36 44-25-22Z" fill="#e9b184" />
        <path d="m81 19-21 25-42 38 22-26Z" fill="#b58157" />
        <path d="m50 25 5 19 19 6-19 5-5 20-5-20-19-5 19-6Z" fill="#1c1814" />
      </template>
      <template v-else-if="variant === 14">
        <path d="M50 1c6 34 10 41 47 49-37 8-41 15-47 49C44 65 40 58 3 50 40 42 44 35 50 1Z" fill="currentColor" />
        <path d="m50 33 5 12 12 5-12 5-5 13-5-13-12-5 12-5Z" fill="#3b2d22" />
        <circle cx="35" cy="37" r="3" fill="#f4d1ad" />
      </template>
      <template v-else-if="variant === 15">
        <path d="m50 1 11 34 34 15-34 12-11 37-11-37L5 50l34-15Z" fill="currentColor" />
        <path d="m50 26 6 18 17 6-17 6-6 19-6-19-17-6 17-6Z" fill="#3a2a20" />
        <circle cx="27" cy="27" r="5" fill="#efba85" />
        <circle cx="71" cy="27" r="3.5" fill="#cb8b59" />
      </template>
      <template v-else-if="variant === 16">
        <path d="M50 1 60 34 94 48 62 59 50 99 39 65 5 51 37 39Z M49 24 43 45 22 51 43 56 50 78 55 55 77 49 56 44Z" fill="currentColor" fill-rule="evenodd" />
        <path d="m17 86 17-26 11 3ZM70 18l16-7-11 14Z" fill="currentColor" />
      </template>
      <template v-else-if="variant === 17">
        <path d="m50 1 10 34 35 15-33 12-12 37-11-37L4 50l34-13Z" fill="currentColor" />
        <path d="m50 1 1 45 44 4-39 5-6 44-3-42L4 50l40-5Z" fill="#edb880" />
        <path d="m23 23 20 12 34 44-23-18Z" fill="#b27b47" />
        <path d="m75 23-17 24-33 31 16-25Z" fill="#d59a61" />
      </template>
      <template v-else-if="variant === 19">
        <path d="m50 1 12 36 36 13-36 12-12 37-12-37L2 50l35-13Z" fill="currentColor" />
        <path d="m50 1 1 46 47 3-43 5-5 44-4-44L2 50l43-5Z" fill="#e8ac76" />
        <path d="m50 18-7 25-26 7 28 5 5 28 5-28 28-5-28-7Z" fill="#a5683c" />
      </template>
      <template v-else>
        <circle v-if="variant % 3 === 0" cx="50" cy="50" r="39" stroke="currentColor" stroke-width="1.4" />
        <path d="M50 2 57 40 98 50 57 58 50 98 42 58 2 50 42 40Z" fill="currentColor" />
        <path d="M19 19 45 39 81 81 57 61Z" fill="currentColor" opacity=".48" />
        <path d="m81 19-21 25-41 37 22-24Z" fill="currentColor" opacity=".58" />
        <path d="M50 29 53 45 69 50 53 54 50 72 46 54 31 50 46 46Z" fill="#272018" opacity=".75" />
      </template>
    </svg>
    <span class="r-brand-word">问阶<small>Interview Studio</small></span>
  </div>
</template>
