'use strict';

const assert = require('node:assert/strict');
const { describe, it, before, after } = require('node:test');
const { startServer, seedAccount, wait } = require('./helpers');

// JPEG mínimo (só os marcadores de início e fim), maior que o limite de foto de movimento.
const BIG_JPEG = Buffer.concat([Buffer.from([0xff, 0xd8]), Buffer.alloc(800 * 1024, 7), Buffer.from([0xff, 0xd9])]);

describe('foto agora', () => {
  let server;
  let account;
  let camera;

  before(async () => {
    account = seedAccount();
    server = await startServer({ seed: account.db });
  });
  after(async () => {
    camera?.close();
    await server.stop();
  });

  const takePhoto = () => server.call('POST', `/api/devices/${account.deviceId}/photo`, { token: account.session });
  const events = async () => (await server.call('GET', `/api/devices/${account.deviceId}/events`, { token: account.session })).data.events;

  it('com a câmera offline, responde na hora', async () => {
    const res = await takePhoto();
    assert.equal(res.status, 409);
    assert.equal(res.data.error, 'camera_offline');
  });

  it('a câmera recebe o pedido, responde com a foto e ela vira um evento "photo"', async () => {
    camera = await server.open(`/ws/device?id=${account.deviceId}`, { Authorization: `Bearer ${account.deviceSecret}` });
    camera.on('message', (data, isBinary) => {
      if (isBinary) return;
      const message = JSON.parse(data.toString());
      if (message.type === 'capture') {
        camera.send(JSON.stringify({ type: 'photo', requestId: message.requestId, snapshot: BIG_JPEG.toString('base64') }));
      }
    });
    await wait(100);

    const res = await takePhoto();
    assert.equal(res.status, 201);
    assert.equal(res.data.event.kind, 'photo');
    assert.equal(res.data.event.snapshot, true);

    const list = await events();
    assert.equal(list[0].id, res.data.event.id);
    const photo = await server.call('GET', `/api/events/${res.data.event.id}/snapshot`, { token: account.session, raw: true });
    assert.ok(photo.data.equals(BIG_JPEG));
  });

  it('foto não conta como último movimento da câmera', async () => {
    const [device] = (await server.call('GET', '/api/devices', { token: account.session })).data.devices;
    assert.equal(device.lastMotionAt, null);
  });

  it('resposta com outro requestId é ignorada', async () => {
    const before = (await events()).length;
    camera.send(JSON.stringify({ type: 'photo', requestId: 'qualquer', snapshot: BIG_JPEG.toString('base64') }));
    await wait(150);
    assert.equal((await events()).length, before);
  });
});
