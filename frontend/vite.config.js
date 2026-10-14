import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'
// 开发代理统一 API 来源；生产 dist 由部署入口反向代理 /api，避免浏览器跨域。
export default defineConfig({ plugins: [vue()], server: { port: 5173, strictPort: true, proxy: { '/api': 'http://127.0.0.1:8080' } }, build: { outDir: 'dist' } })
