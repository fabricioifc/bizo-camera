'use strict';

const crypto = require('node:crypto');

// TOTP (RFC 6238) com HMAC-SHA1, 6 dígitos e passo de 30 segundos.
// Compatível com Google Authenticator, Authy, 1Password e similares.

const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const STEP_MS = 30_000;
const DIGITS = 6;
const WINDOW = 1; // aceita o código anterior e o seguinte, para compensar relógio

function base32Encode(buffer) {
  let bits = 0;
  let value = 0;
  let out = '';
  for (const byte of buffer) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += BASE32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += BASE32[(value << (5 - bits)) & 31];
  return out;
}

function base32Decode(text) {
  const clean = String(text).toUpperCase().replace(/[^A-Z2-7]/g, '');
  let bits = 0;
  let value = 0;
  const out = [];
  for (const char of clean) {
    value = (value << 5) | BASE32.indexOf(char);
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

function generateSecret() {
  return base32Encode(crypto.randomBytes(20));
}

function hotp(keyBuffer, counter) {
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const digest = crypto.createHmac('sha1', keyBuffer).update(message).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const binary =
    ((digest[offset] & 0x7f) << 24) |
    (digest[offset + 1] << 16) |
    (digest[offset + 2] << 8) |
    digest[offset + 3];
  return String(binary % 10 ** DIGITS).padStart(DIGITS, '0');
}

// Retorna o passo de tempo que validou o código, ou null. O chamador guarda o último passo
// usado para impedir que o mesmo código seja aceito duas vezes.
function matchTotp(secret, code, lastStep = -1, now = Date.now()) {
  const text = String(code || '').replace(/\s/g, '');
  if (!/^\d{6}$/.test(text)) return null;
  const key = base32Decode(secret);
  const current = Math.floor(now / STEP_MS);
  for (let offset = -WINDOW; offset <= WINDOW; offset++) {
    const step = current + offset;
    if (step <= lastStep) continue;
    const expected = hotp(key, step);
    if (crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(text))) return step;
  }
  return null;
}

function otpauthUri(secret, email, issuer) {
  const label = encodeURIComponent(`${issuer}:${email}`);
  const params = new URLSearchParams({
    secret,
    issuer,
    algorithm: 'SHA1',
    digits: String(DIGITS),
    period: '30',
  });
  return `otpauth://totp/${label}?${params}`;
}

module.exports = { generateSecret, hotp, matchTotp, otpauthUri, base32Decode, base32Encode };
