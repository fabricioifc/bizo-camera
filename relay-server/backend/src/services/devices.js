'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { randomHex, safeEqual, sha256 } = require('../lib/crypto');
const { sanitizeChanges, sanitizeReport } = require('./camera-settings');

const MOTION_COOLDOWN_MS = 30_000; // no máximo um alerta por câmera a cada 30 s
const MAX_EVENTS_PER_DEVICE = 50;
const RECENT_EVENTS = 20;
const MAX_SNAPSHOT_BYTES = 512 * 1024;
const MAX_NAME_LENGTH = 40;

function parseJson(data) {
  try {
    return JSON.parse(data.toString());
  } catch {
    return null;
  }
}

const eventView = (event) => ({ id: event.id, at: event.at, snapshot: event.snapshot });

// Saúde enviada pela câmera. Só aceita campos conhecidos, com valores plausíveis.
function parseHealth(message) {
  const battery = Number(message.battery);
  const temperature = Number(message.temperature);
  return {
    battery: Number.isFinite(battery) ? Math.min(100, Math.max(0, Math.round(battery))) : null,
    charging: message.charging === true,
    temperature: Number.isFinite(temperature) && temperature > -40 && temperature < 100
      ? Math.round(temperature * 10) / 10
      : null,
    throttled: message.throttled === true,
    updatedAt: Date.now(),
  };
}

/** Câmeras da conta, seus movimentos e as fotos de cada movimento. */
function createDevices({ store, hub, alerts, snapshotDir }) {
  const lastMotionAt = new Map(); // deviceId -> momento do último alerta
  const snapshotPath = (eventId) => path.join(snapshotDir, `${eventId}.jpg`);

  // Foto do movimento, enviada pela câmera em base64. Só aceita JPEG de tamanho razoável.
  function saveSnapshot(eventId, base64) {
    if (typeof base64 !== 'string' || base64.length > Math.ceil(MAX_SNAPSHOT_BYTES / 3) * 4) return false;
    const jpeg = Buffer.from(base64, 'base64');
    if (jpeg.length < 4 || jpeg[0] !== 0xff || jpeg[1] !== 0xd8) return false;
    fs.mkdirSync(snapshotDir, { recursive: true });
    fs.writeFileSync(snapshotPath(eventId), jpeg);
    return true;
  }

  // Movimento: guarda o evento (com foto), avisa quem está assistindo e manda notificação ao dono.
  function recordMotion(deviceId, message) {
    const now = Date.now();
    if (now - (lastMotionAt.get(deviceId) || 0) < MOTION_COOLDOWN_MS) return;
    lastMotionAt.set(deviceId, now);

    const id = randomHex(8);
    const event = { id, deviceId, at: now, snapshot: saveSnapshot(id, message.snapshot) };
    store.data.events.push(event);

    // Mantém só os eventos mais recentes de cada câmera, apagando as fotos antigas.
    const own = store.data.events.filter((item) => item.deviceId === deviceId);
    if (own.length > MAX_EVENTS_PER_DEVICE) {
      const removed = own.slice(0, own.length - MAX_EVENTS_PER_DEVICE);
      const removedIds = new Set(removed.map((item) => item.id));
      for (const item of removed) if (item.snapshot) fs.rm(snapshotPath(item.id), { force: true }, () => {});
      store.data.events = store.data.events.filter((item) => !removedIds.has(item.id));
    }
    store.save();

    hub.notifyViewers(deviceId, { type: 'motion', event: eventView(event) });
    const device = store.data.devices[deviceId];
    if (device) alerts.notifyMotion(deviceId, device, event).catch(() => {});
  }

  function view(id) {
    const device = store.data.devices[id];
    const online = hub.isOnline(id);
    const lastEvent = store.data.events.findLast((event) => event.deviceId === id);
    return {
      id,
      name: device.name,
      online,
      health: online ? hub.health(id) : null,
      lastMotionAt: lastEvent ? lastEvent.at : null,
    };
  }

  return {
    /** A câmera, se ela existir e for desse usuário. */
    owned(userId, id) {
      const device = id ? store.data.devices[id] : undefined;
      return device && device.ownerId === userId ? device : null;
    },

    listFor(userId) {
      return Object.entries(store.data.devices)
        .filter(([, device]) => device.ownerId === userId)
        .map(([id]) => view(id));
    },

    /** Registra o aparelho na conta. O segredo só é devolvido agora; o banco guarda o hash. */
    register(userId, name) {
      const deviceId = randomHex(8);
      const deviceSecret = randomHex(32);
      store.data.devices[deviceId] = {
        ownerId: userId,
        name: String(name || 'Câmera').slice(0, MAX_NAME_LENGTH),
        secretHash: sha256(deviceSecret),
        createdAt: Date.now(),
      };
      store.save();
      return { deviceId, deviceSecret };
    },

    /** Troca o nome. Devolve o nome salvo, ou null se for inválido. */
    rename(device, name) {
      const trimmed = typeof name === 'string' ? name.trim() : '';
      if (!trimmed || trimmed.length > MAX_NAME_LENGTH) return null;
      device.name = trimmed;
      store.save();
      return trimmed;
    },

    /** Remove a câmera, desconecta quem estiver ligado e apaga os movimentos e as fotos. */
    remove(deviceId) {
      delete store.data.devices[deviceId];
      lastMotionAt.delete(deviceId);
      hub.disconnect(deviceId);
      const removed = store.data.events.filter((event) => event.deviceId === deviceId);
      store.data.events = store.data.events.filter((event) => event.deviceId !== deviceId);
      for (const event of removed) if (event.snapshot) fs.rmSync(snapshotPath(event.id), { force: true });
      store.save();
    },

    /** Id da câmera, se o segredo estiver correto. */
    authenticate(deviceId, secret) {
      const device = deviceId ? store.data.devices[deviceId] : undefined;
      return device && secret && safeEqual(sha256(secret), device.secretHash) ? deviceId : null;
    },

    recentEvents(deviceId) {
      return store.data.events
        .filter((event) => event.deviceId === deviceId)
        .slice(-RECENT_EVENTS)
        .reverse()
        .map(eventView);
    },

    /** Caminho da foto do movimento, se ela existir e a câmera for desse usuário. */
    snapshotFor(userId, eventId) {
      const event = store.data.events.find((item) => item.id === eventId);
      if (!event || !event.snapshot || !this.owned(userId, event.deviceId)) return null;
      return snapshotPath(event.id);
    },

    /** Mensagens de texto da câmera: saúde do aparelho, movimento e configurações atuais. */
    handleMessage(deviceId, data) {
      const message = parseJson(data);
      if (message?.type === 'health') hub.setHealth(deviceId, parseHealth(message));
      else if (message?.type === 'motion') recordMotion(deviceId, message);
      else if (message?.type === 'settings') hub.setSettings(deviceId, sanitizeReport(message.settings));
    },

    /**
     * Controle remoto: mensagem de um visualizador pedindo mudanças na câmera.
     * Só valores conhecidos seguem para a câmera, que aplica e responde com o estado novo.
     */
    handleViewerMessage(deviceId, data) {
      const message = parseJson(data);
      if (message?.type !== 'configure') return;
      const changes = sanitizeChanges(message.settings);
      if (Object.keys(changes).length) hub.sendToDevice(deviceId, { type: 'configure', settings: changes });
    },
  };
}

module.exports = { createDevices };
