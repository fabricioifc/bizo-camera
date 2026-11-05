'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const totp = require('../src/lib/totp');

// Segredo do vetor de teste da RFC 4226/6238 ("12345678901234567890" em base32).
const RFC_SECRET = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';

test('HOTP bate com o vetor da RFC 4226', () => {
  const key = totp.base32Decode(RFC_SECRET);
  assert.equal(totp.hotp(key, 0), '755224');
  assert.equal(totp.hotp(key, 1), '287082');
  assert.equal(totp.hotp(key, 9), '520489');
});

test('base32 ida e volta', () => {
  const encoded = totp.base32Encode(Buffer.from('hello-camera'));
  assert.equal(totp.base32Decode(encoded).toString(), 'hello-camera');
});

test('TOTP aceita o passo atual e os vizinhos, e recusa código já usado', () => {
  const key = totp.base32Decode(RFC_SECRET);
  const now = 1_700_000_000_000;
  const step = Math.floor(now / 30_000);
  assert.equal(totp.matchTotp(RFC_SECRET, totp.hotp(key, step), -1, now), step);
  assert.equal(totp.matchTotp(RFC_SECRET, totp.hotp(key, step - 1), -1, now), step - 1);
  assert.equal(totp.matchTotp(RFC_SECRET, totp.hotp(key, step + 1), -1, now), step + 1);
  assert.equal(totp.matchTotp(RFC_SECRET, totp.hotp(key, step + 2), -1, now), null);
  // O mesmo código não vale duas vezes (lastStep já é o passo dele).
  assert.equal(totp.matchTotp(RFC_SECRET, totp.hotp(key, step), step, now), null);
});

test('TOTP recusa formato inválido', () => {
  assert.equal(totp.matchTotp(RFC_SECRET, '12345'), null);
  assert.equal(totp.matchTotp(RFC_SECRET, 'abcdef'), null);
  assert.equal(totp.matchTotp(RFC_SECRET, ''), null);
});

test('URI otpauth tem segredo e emissor', () => {
  const uri = totp.otpauthUri(RFC_SECRET, 'dona@example.com', 'Monitor de Câmera');
  assert.match(uri, /^otpauth:\/\/totp\//);
  assert.match(uri, new RegExp(`secret=${RFC_SECRET}`));
});
