'use strict';

const { WebSocketServer } = require('ws');
const { bearerToken, cookieToken } = require('./http/respond');

const MAX_PAYLOAD = 1024 * 1024;
const PING_MS = 30_000;

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
      return wss.handleUpgrade(req, socket, head, (ws) => hub.attachViewer(deviceId, ws));
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
