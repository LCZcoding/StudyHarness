import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址 http://localhost:8123，context-path 为 /api
// 开发环境通过代理转发，避免跨域；代码中统一使用 /api 前缀
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8123',
        changeOrigin: true
      }
    }
  }
})
