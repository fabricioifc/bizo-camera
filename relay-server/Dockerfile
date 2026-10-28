FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev && npm cache clean --force

COPY src ./src
COPY web ./web

# Os dados (usuários, câmeras e sessões) ficam num volume, fora da imagem.
ENV NODE_ENV=production \
    PORT=8787 \
    DATA_FILE=/data/db.json
RUN mkdir -p /data && chown node:node /data
VOLUME ["/data"]

USER node
EXPOSE 8787

CMD ["node", "src/server.js"]
