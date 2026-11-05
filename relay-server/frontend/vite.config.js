import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// Em desenvolvimento (npm run dev, porta 5173), o Vite recarrega a página a cada alteração e
// encaminha a API e os WebSockets para o backend na porta 8787.
// Em produção (npm run build), gera dist/, que o backend serve sozinho na porta 8787.
const relay = process.env.RELAY_HOST || '127.0.0.1';

export default defineConfig({
  plugins: [vue()],
  server: {
    host: process.env.VITE_HOST || '127.0.0.1',
    port: 5173,
    strictPort: true,
    // No Docker com a pasta do Windows montada, o Linux não recebe os avisos de arquivo alterado:
    // VITE_POLLING=1 faz o Vite conferir os arquivos periodicamente.
    watch: process.env.VITE_POLLING === '1' ? { usePolling: true, interval: 300 } : undefined,
    proxy: {
      '/api': `http://${relay}:8787`,
      '/healthz': `http://${relay}:8787`,
      '/ws': { target: `ws://${relay}:8787`, ws: true },
    },
  },
});
