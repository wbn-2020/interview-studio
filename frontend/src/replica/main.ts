import { createApp } from 'vue'
import ReplicaApp from './ReplicaApp.vue'
import './replica.css'

// 参考场景与正式业务入口隔离，不读取会话、不发送 API、不持久化示例数据。
createApp(ReplicaApp).mount('#app')
