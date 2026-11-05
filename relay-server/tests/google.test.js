'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { test, before, after } = require('node:test');
const google = require('../src/lib/google');

// Chave falsa do "Google": o fetch das chaves públicas é trocado por ela durante os testes.
const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...crypto.createPublicKey(privateKey).export({ format: 'jwk' }), kid: 'test-kid', alg: 'RS256', use: 'sig' };
const realFetch = globalThis.fetch;

before(() => {
  globalThis.fetch = async (url) => {
    if (String(url).includes('googleapis.com/oauth2/v3/certs')) {
      return new Response(JSON.stringify({ keys: [jwk] }), { status: 200 });
    }
    return realFetch(url);
  };
});

after(() => {
  globalThis.fetch = realFetch;
});

function idToken(overrides = {}, { alg = 'RS256', key = privateKey } = {}) {
  const b64 = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url');
  const header = b64({ alg, kid: 'test-kid', typ: 'JWT' });
  const payload = b64({
    iss: 'https://accounts.google.com',
    aud: 'client-123',
    sub: 'google-sub-1',
    email: 'pessoa@example.com',
    email_verified: true,
    nonce: 'nonce-ok',
    exp: Math.floor(Date.now() / 1000) + 300,
    ...overrides,
  });
  const signature = crypto.sign('RSA-SHA256', Buffer.from(`${header}.${payload}`), key).toString('base64url');
  return `${header}.${payload}.${signature}`;
}

const options = { clientId: 'client-123', nonce: 'nonce-ok' };

test('aceita ID token válido', async () => {
  const claims = await google.verifyIdToken(idToken(), options);
  assert.deepEqual(claims, { sub: 'google-sub-1', email: 'pessoa@example.com' });
});

test('login do app (sem nonce) aceita token sem nonce', async () => {
  const claims = await google.verifyIdToken(idToken({ nonce: undefined }), { clientId: 'client-123' });
  assert.equal(claims.sub, 'google-sub-1');
});

const rejected = [
  ['assinatura de outra chave', () => idToken({}, { key: crypto.generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey }), 'bad_signature'],
  ['audiência errada', () => idToken({ aud: 'outro-app' }), 'bad_audience'],
  ['emissor errado', () => idToken({ iss: 'https://evil.example' }), 'bad_issuer'],
  ['token expirado', () => idToken({ exp: Math.floor(Date.now() / 1000) - 10 }), 'token_expired'],
  ['nonce trocado', () => idToken({ nonce: 'outro' }), 'bad_nonce'],
  ['e-mail não verificado', () => idToken({ email_verified: false }), 'email_not_verified'],
  ['algoritmo "none"', () => idToken({}, { alg: 'none' }), 'unexpected_alg'],
];

for (const [name, make, reason] of rejected) {
  test(`recusa ${name}`, async () => {
    await assert.rejects(google.verifyIdToken(make(), options), { message: reason });
  });
}
