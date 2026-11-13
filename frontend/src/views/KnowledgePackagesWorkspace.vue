<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../api/client'
import type { Schema } from '../api/client'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
const KnowledgePackageImport = defineAsyncComponent(() => import('../components/KnowledgePackageImport.vue'))
const route = useRoute(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const enabled = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const topics = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'package-topics']),
  enabled, queryFn: () => read<Schema['TopicList']>('/knowledge/topics') })
const initialId = computed(() => typeof route.query.package === 'string' ? route.query.package : undefined)
function changed() { void queryClient.invalidateQueries({ queryKey: ['knowledge'] }) }
</script>

<template>
  <header class="page-heading"><div><p class="section-label">知识 / 文件交换</p><h1>把整包知识连起来</h1><p class="muted">先确认来源和关系，再创建知识；同一来源包保留稳定的本地映射。</p></div><RouterLink class="text-link" :to="{ name: 'knowledge' }">返回知识库</RouterLink></header>
  <p v-if="topics.error.value" class="notice error" role="alert">{{ errorText(topics.error.value) }} <NButton text :disabled="!enabled" @click="topics.refetch()">重试主题目录</NButton></p>
  <KnowledgePackageImport :enabled="enabled" :topics="topics.data.value?.items ?? []" :initial-id="initialId" @changed="changed" />
</template>
