'use strict';

const assert = require('node:assert/strict');
const { describe, it, before, after } = require('node:test');
const { startServer, seedAccount, wait } = require('./helpers');
const { sanitizeChanges, sanitizeReport } = require('../src/services/camera-settings');

describe('valores do controle remoto', () => {
  it('só passam chaves e valores conhecidos', () => {
    const changes = sanitizeChanges({
      lens: 'front',
      quality: 'ultra',
      rotation: 90,
      motion: 'sim',
      torch: true,
      sensitivity: 'high',
      shell: 'rm -rf /',
    });
    assert.deepEqual(changes, { lens: 'front', rotation: 90, torch: true, sensitivity: 'high' });
    assert.deepEqual(sanitizeChanges({ rotation: 45 }), {});
    assert.deepEqual(sanitizeChanges({ night: true, video: 'não' }), { night: true });
    assert.deepEqual(sanitizeChanges({ zoom: 0.6 }), { zoom: 0.6 });
    assert.deepEqual(sanitizeChanges({ zoom: 1.2345 }), { zoom: 1.2 });
    assert.deepEqual(sanitizeChanges({ zoom: 50 }), {});
    assert.deepEqual(sanitizeChanges({ zoom: '2' }), {});
    assert.deepEqual(sanitizeChanges(null), {});
  });

  it('o estado da câmera inclui se a lanterna existe', () => {
    assert.deepEqual(sanitizeReport({ lens: 'back', torchAvailable: false }), { lens: 'back', torchAvailable: false });
    assert.deepEqual(sanitizeReport({ zoom: 0.6, zoomMin: 0.6, zoomMax: 8 }), { zoom: 0.6, zoomMin: 0.6, zoomMax: 8 });
    assert.deepEqual(sanitizeReport({ focus: 'locked' }), { focus: 'locked' });
    assert.deepEqual(sanitizeReport({ focus: 'outro' }), {});
    assert.deepEqual(sanitizeChanges({ focus: 'locked' }), {});
  });
});

describe('controle remoto pela página', () => {
  let server;
  let account;
  let camera;
  const cameraMessages = [];

  before(async () => {
    account = seedAccount();
    server = await startServer({ seed: account.db });
    camera = await server.open(`/ws/device?id=${account.deviceId}`, { Authorization: `Bearer ${account.deviceSecret}` });
    camera.on('message', (data, isBinary) => {
      if (!isBinary) cameraMessages.push(JSON.parse(data.toString()));
    });
    await wait(100);
  });

  after(async () => {
    camera.close();
    await server.stop();
  });

  const openViewer = () => server.open(`/ws/view?device=${account.deviceId}&token=${account.session}`);
  const configures = () => cameraMessages.filter((m) => m.type === 'configure');

  it('o pedido do site chega à câmera, só com valores válidos', async () => {
    const viewer = await openViewer();
    viewer.send(JSON.stringify({ type: 'configure', settings: { rotation: 270, quality: 'high', lens: 'lateral' } }));
    await wait(150);
    assert.deepEqual(configures().at(-1), { type: 'configure', settings: { rotation: 270, quality: 'high' } });
    viewer.close();
  });

  it('pedido sem nada válido, ou que não é JSON, não chega à câmera', async () => {
    const before = configures().length;
    const viewer = await openViewer();
    viewer.send(JSON.stringify({ type: 'configure', settings: { rotation: 45 } }));
    viewer.send('não é json');
    viewer.send(JSON.stringify({ type: 'start' }));
    await wait(150);
    assert.equal(configures().length, before);
    viewer.close();
  });

  it('o estado da câmera chega aos visualizadores, inclusive a quem abre depois', async () => {
    const first = await openViewer();
    camera.send(JSON.stringify({
      type: 'settings',
      settings: { lens: 'back', quality: 'medium', rotation: 90, motion: true, sensitivity: 'low', torch: false, torchAvailable: true, extra: 1 },
    }));
    await wait(150);
    const expected = { lens: 'back', quality: 'medium', rotation: 90, motion: true, sensitivity: 'low', torch: false, torchAvailable: true };
    assert.deepEqual(first.messages.filter((m) => m.type === 'settings').at(-1).settings, expected);

    const late = await openViewer();
    await wait(100);
    assert.deepEqual(late.messages.find((m) => m.type === 'settings').settings, expected);
    first.close();
    late.close();
  });

  it('o pedido de foco chega à câmera, só com modo válido', async () => {
    const viewer = await openViewer();
    viewer.send(JSON.stringify({ type: 'focus', mode: 'lock', extra: 1 }));
    viewer.send(JSON.stringify({ type: 'focus', mode: 'outro' }));
    viewer.send(JSON.stringify({ type: 'focus', mode: 'auto' }));
    await wait(150);
    assert.deepEqual(cameraMessages.filter((m) => m.type === 'focus'), [{ type: 'focus', mode: 'lock' }, { type: 'focus', mode: 'auto' }]);
    viewer.close();
  });

  it('muitos pedidos seguidos são limitados', async () => {
    const before = configures().length;
    const viewer = await openViewer();
    for (let i = 0; i < 40; i++) viewer.send(JSON.stringify({ type: 'configure', settings: { torch: i % 2 === 0 } }));
    await wait(200);
    assert.equal(configures().length - before, 20);
    viewer.close();
  });
});
