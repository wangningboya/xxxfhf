import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  // 部署在站点子路径下（Nginx: /admin/ -> dist），hash 路由不需要服务端 rewrite
  base: '/admin/',
  plugins: [vue()],
  server: {
    port: 5174,
    // 开发期把 /api 反代到本地服务端，避免跨域与硬编码地址
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
      '/upload': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1200,
  },
});
