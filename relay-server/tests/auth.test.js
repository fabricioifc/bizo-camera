'use strict';

const assert = require('node:assert/strict');
const { describe, it, before, after } = require('node:test');
const totp = require('../src/totp');
const { startServer, seedAccount } = require('./helpers');

describe('login e sessões', () => {
  let server;
  let account;

  before(async () => {
    account = seedAccount();
    server = await startServer({ seed: account.db });
  });
  after(() => server.stop());

  it('responde ao healthcheck', async () => {
    const res = await server.call('GET', '/healthz');
    assert.equal(res.status, 200);
    assert.equal(res.data.ok, true);
  });

  it('login por e-mail e senha não existe mais', async () => {
    assert.equal((await server.call('POST', '/api/auth/login', { body: { email: 'a@b.co', password: 'x' } })).status, 404);
    assert.equal((await server.call('POST', '/api/auth/signup', { body: { email: 'a@b.co', password: 'x' } })).status, 404);
  });

  it('login com Google fica desligado sem credenciais', async () => {
    assert.equal((await server.call('GET', '/api/config')).data.googleEnabled, false);
    assert.equal((await server.call('GET', '/api/auth/google/start')).status, 404);
    assert.equal((await server.call('POST', '/api/auth/google/app', { body: { idToken: 'x' } })).status, 404);
  });

  it('sessão funciona pelo cookie e pelo Bearer', async () => {
    const viaCookie = await server.call('GET', '/api/auth/me', { cookie: `session=${account.session}` });
    const viaBearer = await server.call('GET', '/api/auth/me', { token: account.session });
    assert.equal(viaCookie.data.email, 'dona@example.com');
    assert.equal(viaBearer.data.email, 'dona@example.com');
    assert.equal(viaBearer.data.pending, false);
  });

  it('código 2FA sem desafio válido expira', async () => {
    const res = await server.call('POST', '/api/auth/mfa', { body: { mfaToken: 'nao-existe', code: '123456' } });
    assert.equal(res.status, 401);
    assert.equal(res.data.error, 'mfa_expired');
  });

  it('logout invalida a sessão', async () => {
    const extra = seedAccount();
    const other = await startServer({ seed: extra.db });
    try {
      assert.equal((await other.call('POST', '/api/auth/logout', { token: extra.session })).status, 200);
      assert.equal((await other.call('GET', '/api/auth/me', { token: extra.session })).status, 401);
    } finally {
      await other.stop();
    }
  });
});

describe('login com Google ativo', () => {
  let server;

  before(async () => {
    server = await startServer({
      env: { GOOGLE_CLIENT_ID: 'web-client-123.apps.googleusercontent.com', GOOGLE_CLIENT_SECRET: 'segredo-de-teste' },
    });
  });
  after(() => server.stop());

  it('fluxo web redireciona para o Google', async () => {
    const res = await server.call('GET', '/api/auth/google/start');
    assert.equal(res.status, 302);
    assert.match(res.headers.get('location'), /^https:\/\/accounts\.google\.com\//);
  });

  it('login do app recusa ID token inválido', async () => {
    const res = await server.call('POST', '/api/auth/google/app', { body: { idToken: 'nao-e-um-jwt' } });
    assert.equal(res.status, 401);
    assert.equal(res.data.error, 'invalid_google_token');
  });
});

describe('sessão pendente de 2FA', () => {
  let server;
  let account;

  before(async () => {
    account = seedAccount({ pending: true });
    server = await startServer({ seed: account.db });
  });
  after(() => server.stop());

  it('bloqueia câmeras e registro até ativar o 2FA', async () => {
    const devices = await server.call('GET', '/api/devices', { token: account.session });
    assert.equal(devices.status, 403);
    assert.equal(devices.data.error, 'two_factor_required');
    assert.equal((await server.call('POST', '/api/devices/register', { token: account.session, body: { name: 'x' } })).status, 403);
    await assert.rejects(server.open(`/ws/view?device=${account.deviceId}&token=${account.session}`), /403/);
  });

  it('ativar o 2FA libera a sessão', async () => {
    const setup = await server.call('POST', '/api/account/2fa/setup', { token: account.session });
    assert.equal(setup.status, 200);
    assert.match(setup.data.qr, /^data:image\/png/);

    const wrong = await server.call('POST', '/api/account/2fa/enable', { token: account.session, body: { code: '000000' } });
    assert.equal(wrong.status, 400);

    const code = totp.hotp(totp.base32Decode(setup.data.secret), Math.floor(Date.now() / 30_000));
    const enable = await server.call('POST', '/api/account/2fa/enable', { token: account.session, body: { code } });
    assert.equal(enable.status, 200);
    assert.equal(enable.data.recoveryCodes.length, 8);

    const me = await server.call('GET', '/api/auth/me', { token: account.session });
    assert.equal(me.data.pending, false);
    assert.equal(me.data.twoFactor, true);
    assert.equal((await server.call('GET', '/api/devices', { token: account.session })).status, 200);

    // Recuperação fica guardada só como hash.
    const saved = server.readDb().users['dona@example.com'].totp.recovery;
    assert.ok(saved.every((hash) => /^[a-f0-9]{64}$/.test(hash)));
    assert.ok(!saved.includes(enable.data.recoveryCodes[0]));
  });
});

describe('2FA opcional em desenvolvimento', () => {
  let server;
  let account;

  before(async () => {
    account = seedAccount({ pending: true });
    server = await startServer({ seed: account.db, env: { REQUIRE_2FA: '0' } });
  });
  after(() => server.stop());

  it('libera sessões pendentes sem exigir configuração de 2FA', async () => {
    const me = await server.call('GET', '/api/auth/me', { token: account.session });
    assert.equal(me.status, 200);
    assert.equal(me.data.pending, false);
    assert.equal((await server.call('GET', '/api/devices', { token: account.session })).status, 200);

    const viewer = await server.open(`/ws/view?device=${account.deviceId}&token=${account.session}`);
    viewer.close();
  });
});
