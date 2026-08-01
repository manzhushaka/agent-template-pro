import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')

  return {
    base: env.VITE_PUBLIC_BASE || '/',
    plugins: [vue()],
    server: { proxy: { '/api': env.VITE_PROXY_TARGET || 'http://localhost:8080' } }
  }
})
