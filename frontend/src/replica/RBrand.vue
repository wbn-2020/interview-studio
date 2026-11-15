<script setup lang="ts">
withDefaults(defineProps<{ large?: boolean; variant?: number }>(), { large: false, variant: 0 })
</script>
<template>
  <div class="r-brand" :class="{ 'r-brand-large': large }">
    <svg class="r-emblem" viewBox="0 0 100 100" fill="none" aria-hidden="true">
      <template v-if="variant === 1">
        <path d="M9 50C9 25 27 7 50 7c10 0 19 3 26 7M82 20c8 8 11 18 11 30 0 22-14 39-35 43" stroke="#dca16b" stroke-width="2.5" />
        <path d="M49 93c-13 0-24-6-31-15" stroke="#b67d4e" stroke-width="1" />
        <path d="m50 14 7 29 25 7-25 7-7 36-7-36-27-7 27-7Z" fill="#e4ad76" />
        <path d="m0 50 9-3 4 3-4 3Zm99 0-6-2-3 2 3 2ZM50 0l2 6h-4Z" fill="#dca16b" />
      </template>
      <template v-else-if="variant === 2">
        <path d="m50 1 9 33 17-10-9 19 30 9-31 9 12 19-20-11-8 30-9-31-18 11 9-20-30-8 31-9-9-19 18 10Z" fill="#d9c4a9" />
        <path d="m50 1 1 41 8-8ZM3 52l36-1-7-10Zm47 47 1-37-10 6Zm47-47-36-1 6 10Z" fill="#efdbc0" />
        <path d="m47 37 6 9 12-2-8 10 2 10-10-6-10 5 3-11-8-8 12 1Z" fill="#3d3329" />
        <path d="m49 43 5 6-3 8-6-7Z" fill="#e6d0b2" />
      </template>
      <template v-else-if="variant === 3">
        <path d="m48 1 11 35 35 14-35 12-11 37-11-37L4 50l33-14Zm0 32-5 12-13 5 13 5 5 14 5-14 14-5-14-5Z" fill="#edc3a0" fill-rule="evenodd" />
        <path d="m23 18 3 9 9 3-9 3-3 9-3-9-9-3 9-3Zm51 48 2 6 6 2-6 2-2 7-2-7-6-2 6-2Z" fill="#f5d0ac" />
        <circle cx="73" cy="25" r="2.6" fill="#d6a17a" />
        <circle cx="24" cy="73" r="2" fill="#e5b18b" />
      </template>
      <template v-else-if="variant === 4">
        <path d="m50 1 12 34 34 15-34 15-12 34-12-34L4 50l34-15Z" fill="#c18c60" />
        <path d="m50 1 1 44 45 5-39 5-7 44-5-43L4 50l40-6Z" fill="#d7a277" />
        <path d="m50 33 7 17-7 18-7-18Z" fill="#5a3c27" />
        <path d="m50 43 3 7-3 8-3-8Z" fill="#dba576" />
      </template>
      <template v-else-if="variant === 5">
        <path d="m50 0 11 35 37 15-35 13-13 37-12-37L2 50l36-12Z" fill="#d99b66" />
        <path d="m50 0 1 44 47 6-43 5-5 45-4-44L2 50l41-5Z" fill="#efb57e" />
        <path d="m22 20 26 22 32 37-26-21Z" fill="#b97947" />
        <path d="m77 20-18 28-36 30 23-27Z" fill="#d2955f" />
        <path d="m50 34 6 16-6 17-6-17Z" fill="#775034" />
        <path d="m50 43 3 7-3 8-3-8Z" fill="#e6ad79" />
      </template>
      <template v-else-if="variant === 6">
        <path d="m50 2 9 36 36 12-36 9-9 39-10-39L6 50l34-12Z" stroke="#e1ac7c" stroke-width="2.8" />
        <path d="m50 2 1 44 44 4-40 5-5 43-4-43L6 50l38-5Z" fill="#d29a68" />
        <path d="m50 29 5 15 16 6-16 5-5 17-5-17-17-5 17-6Z" fill="#62462f" stroke="#e9b886" stroke-width="1.8" />
        <path d="m50 39 3 9 9 2-9 3-3 10-3-10-9-3 9-2Z" fill="#e7b886" />
      </template>
      <template v-else-if="variant === 7">
        <ellipse cx="50" cy="50" rx="42" ry="48" fill="#34271e" />
        <path d="m50 0 7 37 31 13-31 8-7 42-8-42-30-8 30-13Z" fill="#d69868" />
        <path d="m50 0 1 43 37 7-34 4-4 46-4-44-34-6 32-6Z" fill="#edb48a" />
        <path d="m50 31 6 14 13 5-13 6-6 17-6-17-13-6 13-5Z" fill="#453021" />
        <path d="m50 41 3 7 7 2-7 3-3 8-3-8-7-3 7-2Z" fill="#eeb98f" />
      </template>
      <template v-else-if="variant === 8">
        <path d="m50 1 10 31-9 17-11-17Z" fill="#e9a571" />
        <path d="m50 99-9-31 10-17 10 17Z" fill="#edaa75" />
        <path d="m1 50 31-11 17 11-17 11Z" fill="#dc9460" />
        <path d="m99 50-31 10-17-10 17-10Z" fill="#e5a16e" />
        <path d="m19 19 20 9 7 15-15-7Zm61 61-20-9-6-15 14 7Z" fill="#a9663a" />
        <path d="m79 20-7 18-15 6 7-15ZM20 79l9-19 15-6-8 15Z" fill="#ad6c40" />
        <path d="m50 38 12 12-12 12-12-12Z" fill="#493020" />
      </template>
      <template v-else-if="variant === 10">
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
      <template v-else-if="variant === 20">
        <path d="m50 0 12 36 38 14-37 13-13 37-13-37L0 50l36-13Z" fill="currentColor" />
        <path d="m50 22 7 21 21 7-21 7-7 21-7-21-21-7 21-7Z" fill="#a96f41" />
        <path d="m50 41 9 9-9 9-9-9Z" fill="#2c2118" />
        <circle cx="29" cy="27" r="5" fill="#e9ad76" />
        <circle cx="73" cy="28" r="4" fill="#cf8b51" />
        <circle cx="29" cy="73" r="3" fill="#e1a575" />
      </template>
      <template v-else-if="variant === 21">
        <path d="m51 0 9 37 35 13-35 10-9 40-11-38L0 51l39-12Z" fill="currentColor" />
        <path d="m51 0-1 46-35-24 28 29-21 25 28-18 1 42 5-44 39-6-40-3 24-28-22 23Z" fill="#e4ad80" />
        <path d="m50 43 8 8-8 8-8-8Z" fill="#6d492f" />
      </template>
      <template v-else-if="variant === 22">
        <path d="m50 0 14 34 34 16-34 15-14 35-15-35L2 50l33-16Z" fill="currentColor" />
        <path d="m50 36 10 14-10 14-10-14Z" fill="#624327" />
        <path d="m50 0 1 37-12 13 11 14v36L35 65 2 50l33-16Z" fill="#f4bf86" opacity=".5" />
      </template>
      <template v-else-if="variant === 23">
        <path d="m50 0 9 38 38 12-38 10-9 40-10-39L3 50l36-11Z" fill="currentColor" />
        <path d="m20 20 28 24 32 36-24-29 24-31-29 25Z" fill="#b77a4a" />
        <path d="m50 21 5 24 23 5-23 6-5 24-5-24-23-6 23-5Z" fill="#38271d" />
        <path d="m50 35 4 12 12 3-12 4-4 13-3-13-13-4 13-3Z" fill="#e4b284" />
      </template>
      <template v-else-if="variant === 24">
        <path d="m50 0 11 36 36 14-35 12-12 38-12-38L3 50l34-12Z" fill="currentColor" />
        <path d="m50 0 1 45 46 5-42 5-5 45-4-44L3 50l41-5Z" fill="#e0c5a7" />
        <path d="m22 22 24 19 33 39-23-21Z" fill="#b99472" />
        <path d="m50 37 8 13-8 13-8-13Z" fill="#86664b" />
        <circle cx="27" cy="71" r="3.5" fill="#c7a685" />
      </template>
      <template v-else-if="variant === 25">
        <circle cx="50" cy="50" r="38" stroke="currentColor" stroke-width="2.5" opacity=".65" />
        <path d="m50 0 9 39 39 11-39 10-9 40-10-40L2 50l38-11Z" fill="currentColor" />
        <path d="m18 19 29 23 35 40-26-26Z" fill="#bca38b" />
        <path d="m50 26 5 19 19 5-19 6-5 19-6-19-18-6 18-5Z" fill="#3d3027" />
        <path d="m50 39 3 9 9 2-9 3-3 10-3-10-9-3 9-2Z" fill="#e9d9c5" />
      </template>
      <template v-else-if="variant === 26">
        <path d="m50 0 14 34 35 16-35 15-14 35-15-35L1 50l34-15Z" fill="currentColor" />
        <path d="m50 25 7 19 18 6-18 7-7 19-7-19-18-7 18-6Z" fill="#30241b" />
        <path d="m25 24 23 19 30 33-21-20 19-31-24 22Z" fill="#cf9467" />
        <path d="m50 35 4 12 12 3-12 4-4 13-4-13-12-4 12-3Z" fill="#f1c69f" />
      </template>
      <template v-else-if="variant === 27">
        <circle cx="50" cy="50" r="49" fill="#27231f" />
        <path d="M50 4c5 30 11 39 43 46-32 6-38 15-43 46C44 65 38 56 7 50 38 43 44 34 50 4Zm0 29-5 12-13 5 13 5 5 13 5-13 13-5-13-5Z" fill="#e0d5c6" fill-rule="evenodd" />
        <path d="m29 21 4 10 9 4-9 3-4 10-3-10-10-3 10-4Zm44 44 3 8 8 3-8 3-3 8-3-8-8-3 8-3Z" fill="#c9bca9" />
        <circle cx="70" cy="29" r="4" fill="#e0d5c6" />
      </template>
      <template v-else-if="variant === 28">
        <path d="m50 3 10 36 35 11-35 10-10 37-10-37L5 50l35-11Z" stroke="#eeb482" stroke-width="5" stroke-linejoin="round" />
        <path d="m50 33 4 13 13 4-13 4-4 14-4-14-13-4 13-4Z" fill="#edb180" />
        <circle cx="30" cy="29" r="4" fill="#f3c69c" />
        <circle cx="70" cy="30" r="3" fill="#c59268" />
        <circle cx="29" cy="72" r="3" fill="#e7ad7c" />
        <circle cx="72" cy="72" r="2" fill="#e7ad7c" />
      </template>
      <template v-else-if="variant === 29">
        <path d="m50 0 13 35 35 15-36 12-12 38-13-38L2 50l35-15Z" fill="#dea366" />
        <path d="m50 0 2 43 46 7-41 5-7 45-5-43L2 50l41-7Z" fill="#f2c593" />
        <path d="m50 39 7 11-7 12-8-12Z" fill="#5e3f29" />
        <path d="m50 44 3 6-3 5-3-5Z" fill="#d6a071" />
      </template>
      <template v-else-if="variant === 30">
        <circle cx="50" cy="50" r="38" stroke="#e7b581" stroke-width="2.2" />
        <path d="M50 2c2 31 11 41 46 48-35 5-44 16-46 48C46 66 38 55 3 50c35-7 43-17 47-48Z" stroke="#e6b27c" stroke-width="2.4" />
        <path d="m50 20 7 22 22 8-22 7-7 23-7-23-22-7 22-8Z" fill="#e9b57e" />
        <path d="m50 36 4 10 10 4-10 3-4 12-4-12-10-3 10-4Z" fill="#5e422b" />
        <path d="m20 21 9 7M74 73l7 7" stroke="#edbc88" stroke-width="1.4" />
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
