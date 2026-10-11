import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端联调目标可用 VITE_API_TARGET 覆盖（如独立验证实例 http://localhost:8081），
// 缺省代理到本地 Spring Boot 默认端口 8080
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true
      }
    }
  }
})
