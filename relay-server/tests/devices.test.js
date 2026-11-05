'use strict';

const assert = require('node:assert/strict');
const { describe, it, before, after } = require('node:test');
const { startServer, seedAccount, randomToken, sha256, wait } = require('./helpers');

// JPEG mínimo (só os marcadores de início e fim): o servidor confere a assinatura do arquivo.
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0xff, 0xd9]);

describe('câmera, saúde e movimento', () => {
  let server;
  let account;
  let stranger;
  let camera;
  let viewer;

  before(async () => {
    account = seedAccount();
    stranger = randomToken();
    account.db.users['outro@example.com'] = { id: 'u2', googleId: 'g2', createdAt: Date.now() };
    account.db.sessions[sha256(stranger)] = { userId: 'u2', expiresAt: Date.now() + 3_600_000 };
    server = await startServer({ seed: account.db });
    camera = await server.open(`/ws/device?id=${account.deviceId}`, { Authorization: `Bearer ${account.deviceSecret}` });
    viewer = await server.open(`/ws/view?device=${account.deviceId}&token=${account.session}`);
    await wait(100);
  });

  after(async () => {
    camera.close();
    viewer.close();
    await server.stop();
  });

  const devices = async (token = account.session) => (await server.call('GET', '/api/devices', { token })).data.devices;

  it('câmera com segredo errado é recusada', async () => {
    await assert.rejects(server.open(`/ws/device?id=${account.deviceId}`, { Authorization: 'Bearer errado' }), /401/);
  });

  it('outra conta não assiste a câmera', async () => {
    await assert.rejects(server.open(`/ws/view?device=${account.deviceId}&token=${stranger}`), /403/);
    assert.equal((await devices(stranger)).length, 0);
  });

  it('saúde chega na lista e no visualizador, com valores conferidos', async () => {
    camera.send(JSON.stringify({ type: 'health', battery: 150, charging: 'sim', temperature: 31.26, throttled: true, extra: 'x' }));
    await wait(150);
    const health = (await devices())[0].health;
    assert.equal(health.battery, 100);
    assert.equal(health.charging, false);
    assert.equal(health.temperature, 31.3);
    assert.equal(health.throttled, true);
    assert.ok(!('extra' in health));
    assert.ok(viewer.messages.some((m) => m.type === 'health'));
  });

  it('mensagem inválida não derruba a câmera', async () => {
    camera.send('isto não é json');
    await wait(100);
    assert.equal((await devices())[0].online, true);
  });

  it('movimento vira evento com foto, uma vez a cada 30 segundos', async () => {
    camera.send(JSON.stringify({ type: 'motion', snapshot: JPEG.toString('base64') }));
    await wait(150);
    camera.send(JSON.stringify({ type: 'motion', snapshot: JPEG.toString('base64') }));
    await wait(150);

    const events = (await server.call('GET', `/api/devices/${account.deviceId}/events`, { token: account.session })).data.events;
    assert.equal(events.length, 1);
    assert.equal(events[0].snapshot, true);
    assert.ok(viewer.messages.some((m) => m.type === 'motion'));
    assert.equal((await devices())[0].lastMotionAt, events[0].at);

    const photo = await server.call('GET', `/api/events/${events[0].id}/snapshot`, { token: account.session, raw: true });
    assert.equal(photo.status, 200);
    assert.ok(photo.data.equals(JPEG));
  });

  it('só o dono vê eventos e fotos', async () => {
    const events = (await server.call('GET', `/api/devices/${account.deviceId}/events`, { token: account.session })).data.events;
    const route = `/api/events/${events[0].id}/snapshot`;
    assert.equal((await server.call('GET', route, { token: stranger })).status, 404);
    assert.equal((await server.call('GET', route)).status, 401);
    assert.equal((await server.call('GET', `/api/devices/${account.deviceId}/events`, { token: stranger })).status, 404);
    assert.equal((await server.call('GET', '/api/events/..%2F..%2Fdb/snapshot', { token: account.session })).status, 404);
  });

  it('assinatura de alertas: valida, não duplica e cancela', async () => {
    const bad = await server.call('POST', '/api/push/subscribe', {
      token: account.session,
      body: { endpoint: 'http://inseguro', keys: { p256dh: 'x', auth: 'y' } },
    });
    assert.equal(bad.status, 400);

    const subscription = { endpoint: 'https://push.example.com/abc', keys: { p256dh: 'chave', auth: 'segredo' } };
    await server.call('POST', '/api/push/subscribe', { token: account.session, body: subscription });
    await server.call('POST', '/api/push/subscribe', { token: account.session, body: subscription });
    assert.equal(server.readDb().push.u1.length, 1);

    await server.call('POST', '/api/push/unsubscribe', { token: account.session, body: { endpoint: subscription.endpoint } });
    assert.equal(server.readDb().push.u1.length, 0);
    assert.ok((await server.call('GET', '/api/push/key')).data.publicKey.length > 60);
  });

  it('câmera desconectada fica offline e sem saúde', async () => {
    camera.close();
    await wait(200);
    const device = (await devices())[0];
    assert.equal(device.online, false);
    assert.equal(device.health, null);
  });
});

describe('arquivos da página', () => {
  let server;

  before(async () => {
    server = await startServer();
  });
  after(() => server.stop());

  it('página aponta app.js e styles.css com versão', async () => {
    const html = (await server.call('GET', '/')).data.toString();
    assert.match(html, /src="\/app\.js\?v=[a-f0-9]{10}"/);
    assert.match(html, /href="\/styles\.css\?v=[a-f0-9]{10}"/);
  });

  it('serve service worker, favicon e ícones', async () => {
    for (const [route, type] of [
      ['/sw.js', 'text/javascript'],
      ['/favicon.ico', 'image/x-icon'],
      ['/icon-192.png', 'image/png'],
      ['/apple-touch-icon.png', 'image/png'],
    ]) {
      const res = await server.call('GET', route, { raw: true });
      assert.equal(res.status, 200, route);
      assert.ok(res.headers.get('content-type').startsWith(type), route);
    }
  });

  it('página tem cabeçalhos de segurança', async () => {
    const res = await server.call('GET', '/');
    assert.match(res.headers.get('content-security-policy'), /default-src 'self'/);
    assert.equal(res.headers.get('x-frame-options'), 'DENY');
  });
});
