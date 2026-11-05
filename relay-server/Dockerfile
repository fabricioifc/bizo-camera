# Imagem de produção: compila o frontend e roda só o backend, que serve a página e a API na porta 8787.

# 1) Frontend: Vue + Vite gera frontend/dist.
FROM node:20-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

# 2) Backend: Node com as dependências de produção e a página compilada.
FROM node:20-alpine
WORKDIR /app/backend
COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY backend/src ./src
COPY --from=frontend /app/frontend/dist /app/frontend/dist

# Os dados (usuários, câmeras e sessões) ficam num volume, fora da imagem.
ENV NODE_ENV=production \
    PORT=8787 \
    DATA_FILE=/data/db.json
RUN mkdir -p /data && chown node:node /data
VOLUME ["/data"]

USER node
EXPOSE 8787

# Marca o container como "unhealthy" se o servidor parar de responder (aparece em `docker ps`).
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.PORT || 8787) + '/healthz').then((r) => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["node", "src/server.js"]
