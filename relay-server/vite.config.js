const path = require('node:path');
const { defineConfig } = require('vite');
const vue = require('@vitejs/plugin-vue');

const root = __dirname;

module.exports = defineConfig({
  root: path.join(root, 'frontend'),
  plugins: [vue()],
  publicDir: false,
  server: {
    host: process.env.VITE_HOST || '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': `http://${process.env.RELAY_HOST || '127.0.0.1'}:8787`,
      '/healthz': `http://${process.env.RELAY_HOST || '127.0.0.1'}:8787`,
      '/ws': {
        target: `ws://${process.env.RELAY_HOST || '127.0.0.1'}:8787`,
        ws: true,
      },
      '/sw.js': `http://${process.env.RELAY_HOST || '127.0.0.1'}:8787`,
      '/favicon.ico': `http://${process.env.RELAY_HOST || '127.0.0.1'}:8787`,
      '/icon-192.png': `http://${process.env.RELAY_HOST || '127.0.0.1'}:8787`,
      '/apple-touch-icon.png': `http://${process.env.RELAY_HOST || '127.0.0.1'}:8787`,
    },
  },
  build: {
    outDir: path.join(root, 'web', 'dist'),
    emptyOutDir: true,
  },
});
