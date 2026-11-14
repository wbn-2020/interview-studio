<script setup lang="ts">
withDefaults(defineProps<{ large?: boolean; variant?: number }>(), { large: false, variant: 0 })
</script>
<template>
  <div class="r-brand" :class="{ 'r-brand-large': large }">
    <svg class="r-emblem" viewBox="0 0 100 100" fill="none" aria-hidden="true">
      <path v-if="variant === 18" d="M50 2 61 37 94 50 61 63 50 98 39 63 6 50 39 37Z M50 29 44 44 29 50 44 56 50 72 56 56 71 50 56 44Z" fill="currentColor" fill-rule="evenodd" />
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
