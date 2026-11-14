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
