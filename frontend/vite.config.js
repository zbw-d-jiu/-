import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'
// 开发/api代理本地后端，生产Axios地址由.env.production确定；根路径部署匹配History路由。
export default defineConfig(({ command }) => ({
  base: '/',
  plugins: [vue()],
  server: { port: 5173, strictPort: true, proxy: { '/api': 'http://127.0.0.1:8080' } },
  // 生产仅输出压缩资产，不发布源码映射；每次构建清理旧dist防止混入过期文件。
  build: { outDir: 'dist', emptyOutDir: true, minify: 'esbuild', sourcemap: false },
  // 只在build移除console/debugger，开发服务器保留调试输出；HTTP gzip交由Nginx实现。
  esbuild: command === 'build' ? { drop: ['console', 'debugger'] } : undefined
}))
