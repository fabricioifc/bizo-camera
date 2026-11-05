'use strict';

const assert = require('node:assert/strict');
const { describe, it, before, after } = require('node:test');
const { startServer, seedAccount, wait } = require('./helpers');

const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0xff, 0xd9]);

// "MP4" mínimo para os testes: só precisa ter "ftyp" no começo.
function fakeMp4(size) {
  const mp4 = Buffer.alloc(size, 1);
  mp4.writeUInt32BE(24, 0);
  mp4.write('ftypisom', 4, 'latin1');
  return mp4;
}

describe('vídeo do movimento', () => {
  let server;
  let account;
  let camera;
  let viewer;
  const clipId = 'abcdef0123456789';
  const oldClipIds = ['1111111111111111', '2222222222222222'];

  before(async () => {
    account = seedAccount();
    // Dois eventos antigos esperando vídeo, para testar o limite de espaço (1 MB).
    account.db.events = oldClipIds.map((id, index) => ({
      id: `e${index}`.padEnd(16, '0'), deviceId: account.deviceId, at: Date.now() - 60_000 + index, snapshot: false, clipId: id,
    }));
    server = await startServer({ seed: account.db, env: { CLIP_STORAGE_MB: '1' } });
    camera = await server.open(`/ws/device?id=${account.deviceId}`, { Authorization: `Bearer ${account.deviceSecret}` });
    viewer = await server.open(`/ws/view?device=${account.deviceId}&token=${account.session}`);
    await wait(100);
  });
  after(async () => {
    camera.close();
    viewer.close();
    await server.stop();
  });

  const events = async () => (await server.call('GET', `/api/devices/${account.deviceId}/events`, { token: account.session })).data.events;
  const sendClip = async (id, mp4) => {
    camera.send(JSON.stringify({ type: 'clip', clipId: id, video: mp4.toString('base64') }));
    await wait(200);
  };

  it('movimento com clipId fica esperando o vídeo', async () => {
    camera.send(JSON.stringify({ type: 'motion', snapshot: JPEG.toString('base64'), clipId }));
    await wait(150);
    const [event] = await events();
    assert.equal(event.clip, false);
    assert.equal(event.clipPending, true);
  });

  it('arquivo que não é MP4 é recusado', async () => {
    await sendClip(clipId, Buffer.from('não é vídeo'));
    assert.equal((await events())[0].clip, false);
  });

  it('o vídeo chega, fica ligado ao evento e o visualizador é avisado', async () => {
    const mp4 = fakeMp4(300 * 1024);
    await sendClip(clipId, mp4);
    const [event] = await events();
    assert.equal(event.clip, true);
    assert.equal(event.clipPending, false);
    assert.ok(viewer.messages.some((m) => m.type === 'event' && m.event.id === event.id && m.event.clip));

    const full = await server.call('GET', `/api/events/${event.id}/clip`, { token: account.session, raw: true });
    assert.equal(full.status, 200);
    assert.equal(full.headers.get('content-type'), 'video/mp4');
    assert.ok(full.data.equals(mp4));
  });

  it('aceita Range (avançar no vídeo) e baixar com nome de arquivo', async () => {
    const [event] = await events();
    const res = await fetch(`${server.base}/api/events/${event.id}/clip?download=1`, {
      headers: { Authorization: `Bearer ${account.session}`, Range: 'bytes=4-11' },
    });
    assert.equal(res.status, 206);
    assert.equal(res.headers.get('content-range'), `bytes 4-11/${300 * 1024}`);
    assert.equal(Buffer.from(await res.arrayBuffer()).toString('latin1'), 'ftypisom');
    assert.match(res.headers.get('content-disposition'), /attachment; filename="Mi-A3-.*\.mp4"/);
  });

  it('acima do limite de espaço, apaga os vídeos mais antigos e mantém o evento', async () => {
    await sendClip(oldClipIds[0], fakeMp4(400 * 1024));
    await sendClip(oldClipIds[1], fakeMp4(400 * 1024));
    // 300 + 400 + 400 KB passa de 1 MB: o vídeo mais antigo (e0) é apagado.
    const list = await events();
    const byId = Object.fromEntries(list.map((event) => [event.id, event]));
    assert.equal(byId['e0'.padEnd(16, '0')].clip, false);
    assert.equal(byId['e1'.padEnd(16, '0')].clip, true);
    assert.equal(list.length, 3);
  });

  it('remove o evento com foto e vídeo', async () => {
    const [event] = await events();
    const res = await server.call('DELETE', `/api/events/${event.id}`, { token: account.session });
    assert.equal(res.status, 200);
    assert.ok(!(await events()).some((item) => item.id === event.id));
    assert.equal((await server.call('GET', `/api/events/${event.id}/clip`, { token: account.session })).status, 404);
    assert.ok(viewer.messages.some((m) => m.type === 'eventRemoved' && m.id === event.id));
  });

  it('sem sessão válida não remove', async () => {
    const [event] = await events();
    const res = await server.call('DELETE', `/api/events/${event.id}`, { token: 'outra-sessao' });
    assert.equal(res.status, 401);
  });
});
