'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { describe, it, before, after } = require('node:test');
const { readApkInfo } = require('../src/lib/apk');
const { makeApk } = require('./apk-fixture');
const { startServer, seedAccount } = require('./helpers');

describe('leitura do APK', () => {
  it('lê pacote, código e nome da versão', () => {
    const info = readApkInfo(makeApk({ versionCode: 57, versionName: '0.4.0' }));
    assert.deepEqual(info, { packageName: 'br.dev.bizo.cameramonitor', versionCode: 57, versionName: '0.4.0' });
  });

  it('recusa o que não é APK', () => {
    assert.throws(() => readApkInfo(Buffer.from('não é um zip')));
  });
});

describe('atualização do app', () => {
  let server;
  let account;
  let pending;

  before(async () => {
    account = seedAccount();
    pending = seedAccount({ pending: true });
    // Segunda sessão, ainda sem 2FA, na mesma conta.
    Object.assign(account.db.sessions, pending.db.sessions);
    server = await startServer({ seed: account.db });
  });
  after(() => server.stop());

  const upload = (body, token = account.session) => fetch(`${server.base}/api/app/release`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/vnd.android.package-archive' },
    body,
  });

  it('sem versão publicada, latest responde 404', async () => {
    const res = await server.call('GET', '/api/app/latest', { token: account.session });
    assert.equal(res.status, 404);
    assert.equal(res.data.error, 'no_release');
  });

  it('exige sessão completa', async () => {
    assert.equal((await server.call('GET', '/api/app/latest')).status, 401);
    assert.equal((await upload(makeApk(), pending.session)).status, 403);
  });

  it('recusa arquivo que não é APK e APK de outro app', async () => {
    let res = await upload(Buffer.from('qualquer coisa'));
    assert.equal(res.status, 400);
    assert.equal((await res.json()).error, 'invalid_apk');
    res = await upload(makeApk({ packageName: 'com.outro.app' }));
    assert.equal(res.status, 400);
    assert.equal((await res.json()).error, 'wrong_package');
  });

  it('publica e entrega o APK com a versão lida do arquivo', async () => {
    const apk = makeApk({ versionCode: 99, versionName: '9.9.0' });
    const res = await upload(apk);
    assert.equal(res.status, 201);
    const published = await res.json();
    assert.equal(published.versionCode, 99);
    assert.equal(published.versionName, '9.9.0');
    assert.equal(published.size, apk.length);
    assert.equal(published.sha256, crypto.createHash('sha256').update(apk).digest('hex'));

    const latest = await server.call('GET', '/api/app/latest', { token: account.session });
    assert.equal(latest.data.versionCode, 99);

    const download = await server.call('GET', '/api/app/download', { token: account.session, raw: true });
    assert.equal(download.status, 200);
    assert.equal(download.headers.get('content-type'), 'application/vnd.android.package-archive');
    assert.ok(download.data.equals(apk));
  });
});
