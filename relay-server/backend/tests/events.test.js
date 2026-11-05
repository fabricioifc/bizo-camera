'use strict';

const assert = require('node:assert/strict');
const { describe, it, before, after } = require('node:test');
const { startServer, seedAccount, sha256, randomToken, wait } = require('./helpers');

const id = (n) => n.toString(16).padStart(16, '0');

describe('lista e exclusão em lote dos eventos', () => {
  let server;
  let account;
  let viewer;
  const strangerDevice = 'ffffffffffffffff';
  const stranger = randomToken();

  before(async () => {
    account = seedAccount();
    // 30 eventos desta câmera (mais que os 20 de antes) e 1 de outra conta.
    account.db.events = Array.from({ length: 30 }, (_, n) => ({
      id: id(n + 1), deviceId: account.deviceId, at: Date.now() - (30 - n) * 60_000, snapshot: false, kind: n % 3 ? 'motion' : 'photo',
    }));
    account.db.users['outro@example.com'] = { id: 'u2', googleId: 'g2', createdAt: Date.now() };
    account.db.sessions[sha256(stranger)] = { userId: 'u2', expiresAt: Date.now() + 3_600_000 };
    account.db.devices[strangerDevice] = { ownerId: 'u2', name: 'Outra', secretHash: sha256(randomToken()), createdAt: Date.now() };
    account.db.events.push({ id: id(999), deviceId: strangerDevice, at: Date.now(), snapshot: false });
    server = await startServer({ seed: account.db });
    viewer = await server.open(`/ws/view?device=${account.deviceId}&token=${account.session}`);
  });
  after(async () => {
    viewer.close();
    await server.stop();
  });

  const list = async () => (await server.call('GET', `/api/devices/${account.deviceId}/events`, { token: account.session })).data.events;
  const bulk = (deviceId, ids, token = account.session) =>
    server.call('DELETE', `/api/devices/${deviceId}/events`, { token, body: { ids } });

  it('a lista traz todos os eventos guardados, do mais novo para o mais antigo', async () => {
    const events = await list();
    assert.equal(events.length, 30);
    assert.equal(events[0].id, id(30));
  });

  it('exclui em lote só os eventos desta câmera', async () => {
    const res = await bulk(account.deviceId, [id(1), id(2), id(3), id(999), 'inválido']);
    assert.equal(res.status, 200);
    assert.equal(res.data.removed, 3);
    assert.equal((await list()).length, 27);
    await wait(100);
    const notice = viewer.messages.find((m) => m.type === 'eventsRemoved');
    assert.deepEqual(notice.ids.sort(), [id(1), id(2), id(3)]);
  });

  it('outra conta não exclui eventos desta câmera', async () => {
    const res = await bulk(account.deviceId, [id(10)], stranger);
    assert.equal(res.status, 404);
    assert.equal((await list()).length, 27);
  });
});
