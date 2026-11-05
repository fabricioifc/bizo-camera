'use strict';

const OPEN = 1;
const MAX_BUFFERED_BYTES = 2 * 1024 * 1024;

function sendJsonWs(ws, payload) {
  if (ws && ws.readyState === OPEN) ws.send(JSON.stringify(payload));
}

function keepAlive(ws) {
  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });
}

/**
 * Conexões ao vivo: liga cada câmera aos seus visualizadores e guarda a última saúde enviada.
 * Não conhece o banco; as mensagens de texto da câmera vão para onDeviceMessage.
 */
function createHub() {
  const cameras = new Map(); // deviceId -> WebSocket da câmera
  const viewers = new Map(); // deviceId -> Set<WebSocket> dos visualizadores
  const health = new Map(); // deviceId -> { battery, charging, temperature, throttled, updatedAt }

  function notifyViewers(deviceId, payload) {
    for (const viewer of viewers.get(deviceId) || []) sendJsonWs(viewer, payload);
  }

  function relayFrame(deviceId, data) {
    for (const viewer of viewers.get(deviceId) || []) {
      // Descarta quadros para quem está com a conexão lenta, em vez de acumular atraso.
      if (viewer.readyState === OPEN && viewer.bufferedAmount < MAX_BUFFERED_BYTES) {
        viewer.send(data, { binary: true });
      }
    }
  }

  return {
    notifyViewers,
    isOnline: (deviceId) => cameras.has(deviceId),
    onlineCount: () => cameras.size,
    health: (deviceId) => health.get(deviceId) || null,

    setHealth(deviceId, value) {
      health.set(deviceId, value);
      notifyViewers(deviceId, { type: 'health', health: value });
    },

    /** Câmera conectada: envia "start" se já houver visualizadores e repassa os quadros. */
    attachDevice(deviceId, ws, onDeviceMessage) {
      const previous = cameras.get(deviceId);
      if (previous) previous.terminate();
      cameras.set(deviceId, ws);
      keepAlive(ws);

      ws.on('message', (data, isBinary) => {
        if (isBinary) relayFrame(deviceId, data);
        else onDeviceMessage(deviceId, data);
      });

      ws.on('close', () => {
        if (cameras.get(deviceId) !== ws) return;
        cameras.delete(deviceId);
        health.delete(deviceId);
        notifyViewers(deviceId, { type: 'status', online: false });
      });

      notifyViewers(deviceId, { type: 'status', online: true });
      if (viewers.get(deviceId)?.size) sendJsonWs(ws, { type: 'start' });
    },

    /** Visualizador: a primeira conexão faz a câmera começar a transmitir; a última, parar. */
    attachViewer(deviceId, ws) {
      let set = viewers.get(deviceId);
      if (!set) {
        set = new Set();
        viewers.set(deviceId, set);
      }
      const wasIdle = set.size === 0;
      set.add(ws);
      keepAlive(ws);

      sendJsonWs(ws, { type: 'status', online: cameras.has(deviceId) });
      if (health.has(deviceId)) sendJsonWs(ws, { type: 'health', health: health.get(deviceId) });
      if (wasIdle) sendJsonWs(cameras.get(deviceId), { type: 'start' });

      ws.on('close', () => {
        set.delete(ws);
        if (set.size === 0) {
          viewers.delete(deviceId);
          sendJsonWs(cameras.get(deviceId), { type: 'stop' });
        }
      });
    },

    /** Câmera removida da conta: manda parar e derruba a câmera e os visualizadores. */
    disconnect(deviceId) {
      const camera = cameras.get(deviceId);
      const watching = viewers.get(deviceId);
      cameras.delete(deviceId);
      viewers.delete(deviceId);
      health.delete(deviceId);
      if (camera) {
        sendJsonWs(camera, { type: 'stop' });
        camera.close(1008, 'camera removed');
      }
      for (const viewer of watching || []) viewer.close(1008, 'camera removed');
    },
  };
}

module.exports = { createHub };
