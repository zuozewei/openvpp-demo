import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 后端联调时将 /api 代理到本地 Spring Boot 服务，目标地址按实际环境调整
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
