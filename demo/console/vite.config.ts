import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

// redis-iris-java 管理控制台 — 由 Figma Make 导出 Demo 还原而来（剔除 Figma 专用插件）
export default defineConfig({
  base: '/',
  build: {
    sourcemap: false,
  },
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      // 双进程分流：
      //   api  = 中间件 fat jar :8080（REST 数据/运维面 + MCP 服务面 + actuator）
      //   demo = 演示 fat jar   :8090（8081 被 iris-debezium 容器映射占用）（/api/v1/agent/* 聊天循环，AgentController 所在模块）
      // 前缀匹配按声明顺序，'/api/v1/agent' 必须放在 '/api' 之前。
      // agent-keys 管理在 /api/v1/admin/*，不受 /api/v1/agent 前缀影响，走中间件。
      '/api/v1/agent': { target: 'http://localhost:8090', changeOrigin: true },
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
      '/mcp': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  preview: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api/v1/agent': { target: 'http://localhost:8090', changeOrigin: true },
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
      '/mcp': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
