'use strict';

const LOW_BATTERY = 20;
const BATTERY_RECOVERED = 30;
const REPEAT_COOLDOWN_MS = 10 * 60 * 1000; // o mesmo aviso, no máximo a cada 10 minutos
const RECENTLY_SEEN_MS = 24 * 60 * 60 * 1000;

/**
 * Vigia das câmeras: avisa o dono (notificação no navegador) quando uma câmera fica offline,
 * volta, sai da tomada, fica com a bateria baixa ou esquenta.
 * Recebe os eventos do hub (conexão e saúde); o estado fica só em memória, exceto lastSeenAt.
 */
function createWatchdog({ store, hub, alerts, offlineAfterMs }) {
  const state = new Map(); // deviceId -> { timer, offlineAlerted, health, lowBattery, hot, lastAlertAt }

  function entry(deviceId) {
    let item = state.get(deviceId);
    if (!item) {
      item = { timer: null, offlineAlerted: false, health: null, lowBattery: false, hot: false, lastAlertAt: {} };
      state.set(deviceId, item);
    }
    return item;
  }

  function notify(deviceId, kind, title, body, { repeatable = false } = {}) {
    const device = store.data.devices[deviceId];
    if (!device) return;
    const item = entry(deviceId);
    const now = Date.now();
    if (!repeatable && now - (item.lastAlertAt[kind] || 0) < REPEAT_COOLDOWN_MS) return;
    item.lastAlertAt[kind] = now;
    alerts.notifyDevice(deviceId, device, { kind, title, body: body(device.name), at: now }).catch(() => {});
  }

  // Câmera sem conexão: espera um pouco (quedas rápidas de Wi-Fi não contam) e então avisa.
  function scheduleOffline(deviceId, delay = offlineAfterMs) {
    const item = entry(deviceId);
    clearTimeout(item.timer);
    item.timer = setTimeout(() => {
      item.timer = null;
      if (!store.data.devices[deviceId] || hub.isOnline(deviceId)) return;
      item.offlineAlerted = true;
      const minutes = Math.max(1, Math.round(offlineAfterMs / 60_000));
      notify(deviceId, 'offline', 'Câmera offline', (name) => `${name} está sem conexão há ${minutes} min.`, { repeatable: true });
    }, delay);
    item.timer.unref?.();
  }

  function onStatus(deviceId, online) {
    const device = store.data.devices[deviceId];
    if (!device) return;
    device.lastSeenAt = Date.now();
    store.save();
    const item = entry(deviceId);
    if (online) {
      clearTimeout(item.timer);
      item.timer = null;
      if (item.offlineAlerted) {
        item.offlineAlerted = false;
        notify(deviceId, 'online', 'Câmera de volta', (name) => `${name} voltou a ficar online.`, { repeatable: true });
      }
    } else {
      // A próxima saúde, ao reconectar, não é comparada com a de antes da queda.
      item.health = null;
      scheduleOffline(deviceId);
    }
  }

  function onHealth(deviceId, health) {
    const item = entry(deviceId);
    const previous = item.health;
    item.health = health;
    const battery = health.battery;

    if (previous && previous.charging && !health.charging) {
      const level = battery !== null ? ` (bateria em ${battery}%)` : '';
      notify(deviceId, 'power', 'Câmera fora da tomada', (name) => `${name} parou de carregar${level}.`);
    }

    if (battery !== null && !health.charging && battery <= LOW_BATTERY && !item.lowBattery) {
      item.lowBattery = true;
      notify(deviceId, 'battery', 'Bateria baixa', (name) => `${name} está com ${battery}% de bateria.`);
    } else if (item.lowBattery && (health.charging || (battery !== null && battery >= BATTERY_RECOVERED))) {
      item.lowBattery = false;
    }

    if (health.throttled && !item.hot) {
      item.hot = true;
      const degrees = health.temperature !== null ? ` (${Math.round(health.temperature)} °C)` : '';
      notify(deviceId, 'hot', 'Câmera esquentando', (name) => `${name} está quente${degrees}; a qualidade foi reduzida.`);
    } else if (!health.throttled) {
      item.hot = false;
    }
  }

  hub.subscribe({ onStatus, onHealth });

  return {
    /**
     * Ao subir o servidor: as câmeras vistas nas últimas 24 h têm um prazo para reconectar;
     * se não voltarem, o dono é avisado (o servidor pode ter ficado fora do ar junto).
     */
    start() {
      const now = Date.now();
      for (const [deviceId, device] of Object.entries(store.data.devices)) {
        if (device.lastSeenAt && now - device.lastSeenAt < RECENTLY_SEEN_MS) scheduleOffline(deviceId);
      }
    },
  };
}

module.exports = { createWatchdog, LOW_BATTERY };
