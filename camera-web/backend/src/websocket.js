'use strict';

const { WebSocketServer } = require('ws');
const { bearerToken, cookieToken } = require('./http/respond');

// Maior mensagem aceita: o vídeo do movimento (até 8 MB, em base64, com folga para o JSON).
const MAX_PAYLOAD = 12 * 1024 * 1024;
const PING_MS = 30_000;
// Comandos do visualizador (controle remoto): mensagens pequenas e poucas por vez.
const MAX_VIEWER_MESSAGE = 2048;
const VIEWER_BURST = 20;
const VIEWER_WINDOW_MS = 10_000;

// Controle remoto: repassa os comandos do visualizador, com limite de tamanho e de quantidade.
function listenToViewer(ws, deviceId, devices) {
  let windowStart = Date.now();
  let count = 0;
  ws.on('message', (data, isBinary) => {
    if (isBinary || data.length > MAX_VIEWER_MESSAGE) return;
    const now = Date.now();
    if (now - windowStart > VIEWER_WINDOW_MS) {
      windowStart = now;
      count = 0;
    }
    count += 1;
    if (count > VIEWER_BURST) return;
    devices.handleViewerMessage(deviceId, data);
  });
}

function reject(socket, status) {
  socket.write(`HTTP/1.1 ${status}\r\nConnection: close\r\n\r\n`);
  socket.destroy();
}

/**
 * WebSockets no mesmo servidor HTTP:
 * - /ws/device?id=...: a câmera, autenticada pelo deviceSecret no Bearer;
 * - /ws/view?device=...: o visualizador, com a sessão do dono da câmera.
 */
function attachWebSockets(server, { config, sessions, devices, hub }) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD });

  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://localhost');

    if (url.pathname === '/ws/device') {
      const deviceId = devices.authenticate(url.searchParams.get('id'), bearerToken(req));
      if (!deviceId) return reject(socket, '401 Unauthorized');
      return wss.handleUpgrade(req, socket, head, (ws) => hub.attachDevice(deviceId, ws, devices.handleMessage));
    }

    if (url.pathname === '/ws/view') {
      const deviceId = url.searchParams.get('device');
      // A página web envia o cookie; o token na URL continua aceito por compatibilidade.
      const token = url.searchParams.get('token') || cookieToken(req);
      const userId = sessions.userId(token);
      const pending = config.require2fa && sessions.isPending(token);
      if (!userId || pending || !devices.owned(userId, deviceId)) return reject(socket, '403 Forbidden');
      return wss.handleUpgrade(req, socket, head, (ws) => {
        hub.attachViewer(deviceId, ws);
        listenToViewer(ws, deviceId, devices);
      });
    }

    return reject(socket, '404 Not Found');
  });

  // Detecta conexões mortas (queda de Wi-Fi/LTE sem aviso).
  const timer = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      ws.ping();
    }
  }, PING_MS);
  timer.unref();

  return wss;
}

module.exports = { attachWebSockets };
