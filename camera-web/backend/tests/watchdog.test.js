'use strict';

const assert = require('node:assert/strict');
const { describe, it, beforeEach } = require('node:test');
const { createWatchdog } = require('../src/services/watchdog');
const { startServer, seedAccount, wait } = require('./helpers');

// Vigia com câmera, hub e envio de notificações falsos; offline vira alerta em 50 ms.
function setup({ devices } = {}) {
  const online = new Set();
  const sent = [];
  let subscriber;
  const store = { data: { devices: devices || { cam1: { ownerId: 'u1', name: 'Garagem' } } }, save() {} };
  const hub = { isOnline: (id) => online.has(id), subscribe: (s) => { subscriber = s; } };
  const alerts = { notifyDevice: async (deviceId, device, payload) => sent.push({ deviceId, ...payload }) };
  const watchdog = createWatchdog({ store, hub, alerts, offlineAfterMs: 50 });
  return {
    watchdog,
    store,
    sent,
    kinds: () => sent.map((item) => item.kind),
    connect(id = 'cam1') {
      online.add(id);
      subscriber.onStatus(id, true);
    },
    disconnect(id = 'cam1') {
      online.delete(id);
      subscriber.onStatus(id, false);
    },
    health(values, id = 'cam1') {
      subscriber.onHealth(id, { battery: 80, charging: true, temperature: 30, throttled: false, ...values });
    },
  };
}

describe('alertas de câmera', () => {
  let t;
  beforeEach(() => {
    t = setup();
  });

  it('avisa quando a câmera fica offline e quando volta', async () => {
    t.connect();
    t.disconnect();
    await wait(100);
    assert.deepEqual(t.kinds(), ['offline']);
    assert.match(t.sent[0].body, /Garagem está sem conexão/);
    t.connect();
    assert.deepEqual(t.kinds(), ['offline', 'online']);
  });

  it('queda rápida (volta antes do prazo) não gera aviso', async () => {
    t.connect();
    t.disconnect();
    await wait(10);
    t.connect();
    await wait(100);
    assert.deepEqual(t.kinds(), []);
  });

  it('guarda quando a câmera foi vista pela última vez', () => {
    t.disconnect();
    assert.ok(t.store.data.devices.cam1.lastSeenAt > 0);
  });

  it('avisa ao sair da tomada, mas não na primeira leitura', () => {
    t.connect();
    t.health({ charging: false });
    assert.deepEqual(t.kinds(), []);
    t.health({ charging: true });
    t.health({ charging: false, battery: 64 });
    assert.deepEqual(t.kinds(), ['power']);
    assert.match(t.sent[0].body, /64%/);
  });

  it('bateria baixa avisa uma vez até recarregar', () => {
    t.health({ charging: false, battery: 19 });
    t.health({ charging: false, battery: 15 });
    assert.deepEqual(t.kinds(), ['battery']);
    t.health({ charging: true, battery: 15 });
    t.health({ charging: false, battery: 18 });
    // Saiu da tomada de novo (avisa), mas "bateria baixa" tem intervalo mínimo de 10 minutos.
    assert.deepEqual(t.kinds(), ['battery', 'power']);
  });

  it('avisa quando esquenta', () => {
    t.health({ throttled: true, temperature: 44.6 });
    t.health({ throttled: true, temperature: 45 });
    assert.deepEqual(t.kinds(), ['hot']);
    assert.match(t.sent[0].body, /45 °C/);
  });

  it('câmera removida não gera aviso', async () => {
    t.connect();
    t.disconnect();
    delete t.store.data.devices.cam1;
    await wait(100);
    assert.deepEqual(t.kinds(), []);
  });

  it('ao subir o servidor, câmera vista há pouco que não reconecta gera aviso', async () => {
    const recent = setup({ devices: { cam1: { ownerId: 'u1', name: 'Sala', lastSeenAt: Date.now() - 60_000 } } });
    recent.watchdog.start();
    await wait(100);
    assert.deepEqual(recent.kinds(), ['offline']);
  });
});

describe('lista de câmeras', () => {
  it('mostra desde quando a câmera está offline', async () => {
    const account = seedAccount();
    const server = await startServer({ seed: account.db });
    try {
      const camera = await server.open(`/ws/device?id=${account.deviceId}`, { Authorization: `Bearer ${account.deviceSecret}` });
      await wait(100);
      const before = Date.now();
      camera.close();
      await wait(150);
      const [device] = (await server.call('GET', '/api/devices', { token: account.session })).data.devices;
      assert.equal(device.online, false);
      assert.ok(device.lastSeenAt >= before - 50);
    } finally {
      await server.stop();
    }
  });
});
