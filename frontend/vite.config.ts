import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: `../artifacts/frontend/dist-${new Date().toISOString().replaceAll(':', '-').replace('.', '-')}`,
    target: 'es2022',
    sourcemap: false,
    emptyOutDir: false,
    rolldownOptions: { input: ['index.html', 'replica.html'] },
  },
})
