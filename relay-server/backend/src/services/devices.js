'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { randomHex, safeEqual, sha256 } = require('../lib/crypto');
const { sanitizeChanges, sanitizeReport } = require('./camera-settings');

const MOTION_COOLDOWN_MS = 30_000; // no máximo um alerta por câmera a cada 30 s
const MAX_EVENTS_PER_DEVICE = 50;
const RECENT_EVENTS = 20;
const MAX_SNAPSHOT_BYTES = 512 * 1024;
const MAX_PHOTO_BYTES = 3 * 1024 * 1024; // "Foto agora", em qualidade alta
const PHOTO_TIMEOUT_MS = 20_000;
const MAX_CLIP_BYTES = 8 * 1024 * 1024; // vídeo curto do movimento (MP4)
const CLIP_WAIT_MS = 2 * 60 * 1000; // a câmera manda o vídeo alguns segundos depois do movimento
const ID_PATTERN = /^[a-f0-9]{16}$/;
const MAX_NAME_LENGTH = 40;

function parseJson(data) {
  try {
    return JSON.parse(data.toString());
  } catch {
    return null;
  }
}

// kind: "motion" (movimento detectado) ou "photo" (foto tirada pelo site).
// clip: há vídeo; clipPending: o vídeo ainda está sendo gravado/enviado pela câmera.
const eventView = (event) => ({
  id: event.id,
  at: event.at,
  snapshot: event.snapshot,
  kind: event.kind || 'motion',
  clip: Boolean(event.clip),
  clipPending: Boolean(event.clipId && !event.clip && Date.now() - event.at < CLIP_WAIT_MS),
});
const isMotion = (event) => (event.kind || 'motion') === 'motion';

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
// Versão do app informada pela câmera (junto com a saúde). Câmeras com app antigo não mandam.
function parseAppVersion(message) {
  const code = Number(message.appVersionCode);
  if (!Number.isInteger(code) || code <= 0 || code > 1e9) return null;
  const text = (value) => (typeof value === 'string' && value.length <= 64 ? value : null);
  return { code, name: text(message.appVersionName) || String(code), package: text(message.appPackage) };
}

function createDevices({ store, hub, alerts, releases, snapshotDir, clipStorageBytes = 500 * 1024 * 1024 }) {
  const lastMotionAt = new Map(); // deviceId -> momento do último alerta
  const pendingPhotos = new Map(); // requestId -> { deviceId, resolve, timer }
  const snapshotPath = (eventId) => path.join(snapshotDir, `${eventId}.jpg`);
  const clipPath = (eventId) => path.join(snapshotDir, `${eventId}.mp4`);

  // Apaga a foto e o vídeo de um evento.
  function removeEventFiles(event) {
    if (event.snapshot) fs.rm(snapshotPath(event.id), { force: true }, () => {});
    if (event.clip) fs.rm(clipPath(event.id), { force: true }, () => {});
  }

  // Limite de espaço dos vídeos: apaga os mais antigos (o evento e a foto continuam).
  function enforceClipStorage() {
    const clips = store.data.events.filter((event) => event.clip).sort((a, b) => a.at - b.at);
    let total = clips.reduce((sum, event) => sum + (event.clipSize || 0), 0);
    for (const event of clips) {
      if (total <= clipStorageBytes) break;
      fs.rm(clipPath(event.id), { force: true }, () => {});
      total -= event.clipSize || 0;
      event.clip = false;
      delete event.clipSize;
      hub.notifyViewers(event.deviceId, { type: 'event', event: eventView(event) });
    }
  }

  // Vídeo do movimento: chega depois do evento, identificado pelo clipId que a câmera mandou junto.
  function receiveClip(deviceId, message) {
    if (typeof message.clipId !== 'string' || !ID_PATTERN.test(message.clipId)) return;
    const event = store.data.events.find((item) => item.deviceId === deviceId && item.clipId === message.clipId);
    if (!event || event.clip || Date.now() - event.at > CLIP_WAIT_MS) return;
    if (typeof message.video !== 'string' || message.video.length > Math.ceil(MAX_CLIP_BYTES / 3) * 4) return;
    const mp4 = Buffer.from(message.video, 'base64');
    // Arquivo MP4: "ftyp" logo no começo.
    if (mp4.length < 12 || mp4.toString('latin1', 4, 8) !== 'ftyp') return;
    fs.mkdirSync(snapshotDir, { recursive: true });
    fs.writeFileSync(clipPath(event.id), mp4);
    event.clip = true;
    event.clipSize = mp4.length;
    delete event.clipId;
    enforceClipStorage();
    store.save();
    hub.notifyViewers(deviceId, { type: 'event', event: eventView(event) });
  }

  // Foto enviada pela câmera em base64. Só aceita JPEG de tamanho razoável.
  function saveSnapshot(eventId, base64, maxBytes = MAX_SNAPSHOT_BYTES) {
    if (typeof base64 !== 'string' || base64.length > Math.ceil(maxBytes / 3) * 4) return false;
    const jpeg = Buffer.from(base64, 'base64');
    if (jpeg.length < 4 || jpeg[0] !== 0xff || jpeg[1] !== 0xd8) return false;
    fs.mkdirSync(snapshotDir, { recursive: true });
    fs.writeFileSync(snapshotPath(eventId), jpeg);
    return true;
  }

  // Guarda o evento e mantém só os mais recentes de cada câmera, apagando as fotos antigas.
  function addEvent(event) {
    store.data.events.push(event);
    const own = store.data.events.filter((item) => item.deviceId === event.deviceId);
    if (own.length > MAX_EVENTS_PER_DEVICE) {
      const removed = own.slice(0, own.length - MAX_EVENTS_PER_DEVICE);
      const removedIds = new Set(removed.map((item) => item.id));
      for (const item of removed) removeEventFiles(item);
      store.data.events = store.data.events.filter((item) => !removedIds.has(item.id));
    }
    store.save();
  }

  // "Foto agora": a câmera respondeu ao pedido com a foto. Guarda junto com os movimentos, sem alerta.
  function receivePhoto(deviceId, message) {
    const pending = typeof message.requestId === 'string' ? pendingPhotos.get(message.requestId) : null;
    if (!pending || pending.deviceId !== deviceId) return;
    pendingPhotos.delete(message.requestId);
    clearTimeout(pending.timer);
    const id = randomHex(8);
    if (!saveSnapshot(id, message.snapshot, MAX_PHOTO_BYTES)) {
      pending.resolve({ error: 'invalid_photo' });
      return;
    }
    const event = { id, deviceId, at: Date.now(), snapshot: true, kind: 'photo' };
    addEvent(event);
    hub.notifyViewers(deviceId, { type: 'motion', event: eventView(event) });
    pending.resolve({ event: eventView(event) });
  }

  // Movimento: guarda o evento (com foto), avisa quem está assistindo e manda notificação ao dono.
  function recordMotion(deviceId, message) {
    const now = Date.now();
    if (now - (lastMotionAt.get(deviceId) || 0) < MOTION_COOLDOWN_MS) return;
    lastMotionAt.set(deviceId, now);

    const id = randomHex(8);
    const event = { id, deviceId, at: now, snapshot: saveSnapshot(id, message.snapshot) };
    // A câmera grava o vídeo e manda depois, com este identificador.
    if (typeof message.clipId === 'string' && ID_PATTERN.test(message.clipId)) event.clipId = message.clipId;
    addEvent(event);

    hub.notifyViewers(deviceId, { type: 'motion', event: eventView(event) });
    const device = store.data.devices[deviceId];
    if (device) alerts.notifyMotion(deviceId, device, event).catch(() => {});
  }

  // Versão do app da câmera e a atualização disponível (versão publicada mais nova, do mesmo pacote).
  function appView(device) {
    if (!device.app) return null;
    const latest = releases?.latest();
    const pending = Boolean(latest && latest.packageName === device.app.package && latest.versionCode > device.app.code);
    return {
      versionCode: device.app.code,
      versionName: device.app.name,
      update: pending ? { versionCode: latest.versionCode, versionName: latest.versionName } : null,
    };
  }

  // Guarda a versão do app (só quando muda), para ela continuar aparecendo com a câmera offline.
  function rememberAppVersion(deviceId, message) {
    const device = store.data.devices[deviceId];
    const app = parseAppVersion(message);
    if (!device || !app) return;
    const known = device.app;
    if (known && known.code === app.code && known.name === app.name && known.package === app.package) return;
    device.app = app;
    store.save();
  }

  function view(id) {
    const device = store.data.devices[id];
    const online = hub.isOnline(id);
    const lastEvent = store.data.events.findLast((event) => event.deviceId === id && isMotion(event));
    return {
      id,
      name: device.name,
      online,
      health: online ? hub.health(id) : null,
      lastMotionAt: lastEvent ? lastEvent.at : null,
      // Última conexão ou desconexão: com a câmera offline, mostra desde quando.
      lastSeenAt: device.lastSeenAt || null,
      app: appView(device),
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
      for (const event of removed) removeEventFiles(event);
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

    /** Caminho do vídeo do evento, se existir e a câmera for desse usuário. */
    clipFor(userId, eventId) {
      const event = store.data.events.find((item) => item.id === eventId);
      if (!event || !event.clip || !this.owned(userId, event.deviceId)) return null;
      return clipPath(event.id);
    },

    /** Dados do evento para nomear o arquivo baixado, se for desse usuário. */
    eventFor(userId, eventId) {
      const event = store.data.events.find((item) => item.id === eventId);
      if (!event) return null;
      const device = this.owned(userId, event.deviceId);
      return device ? { event, device } : null;
    },

    /** Remove o evento (foto e vídeo). Devolve false se não existir ou não for desse usuário. */
    removeEvent(userId, eventId) {
      const found = this.eventFor(userId, eventId);
      if (!found) return false;
      removeEventFiles(found.event);
      store.data.events = store.data.events.filter((item) => item.id !== eventId);
      store.save();
      hub.notifyViewers(found.event.deviceId, { type: 'eventRemoved', id: eventId });
      return true;
    },

    /**
     * "Foto agora": pede uma foto em qualidade alta à câmera e espera a resposta.
     * Resolve com { event } ou { error: 'camera_offline' | 'busy' | 'timeout' | 'invalid_photo' }.
     */
    takePhoto(deviceId) {
      if (!hub.isOnline(deviceId)) return Promise.resolve({ error: 'camera_offline' });
      for (const pending of pendingPhotos.values()) {
        if (pending.deviceId === deviceId) return Promise.resolve({ error: 'busy' });
      }
      const requestId = randomHex(8);
      return new Promise((resolve) => {
        const timer = setTimeout(() => {
          pendingPhotos.delete(requestId);
          resolve({ error: 'timeout' });
        }, PHOTO_TIMEOUT_MS);
        pendingPhotos.set(requestId, { deviceId, resolve, timer });
        hub.sendToDevice(deviceId, { type: 'capture', requestId });
      });
    },

    /** Mensagens de texto da câmera: saúde do aparelho, movimento e configurações atuais. */
    handleMessage(deviceId, data) {
      const message = parseJson(data);
      if (message?.type === 'health') {
        rememberAppVersion(deviceId, message);
        hub.setHealth(deviceId, parseHealth(message));
      }
      else if (message?.type === 'motion') recordMotion(deviceId, message);
      else if (message?.type === 'photo') receivePhoto(deviceId, message);
      else if (message?.type === 'clip') receiveClip(deviceId, message);
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
