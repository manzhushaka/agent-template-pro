import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { quasar, transformAssetUrls } from '@quasar/vite-plugin'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')

  return {
    base: env.VITE_PUBLIC_BASE || '/',
    plugins: [
      vue({ template: { transformAssetUrls } }),
      quasar({
        // 使用绝对路径，保证注入到 quasar/src/css/index.sass 时也能解析
        sassVariables: fileURLToPath(new URL('./src/quasar-variables.sass', import.meta.url)),
        autoImportComponentCase: 'kebab',
      }),
    ],
    server: { proxy: { '/api': env.VITE_PROXY_TARGET || 'http://localhost:8080' } }
  }
})
