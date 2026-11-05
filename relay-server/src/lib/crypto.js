'use strict';

const crypto = require('node:crypto');

const sha256 = (value) => crypto.createHash('sha256').update(value).digest('hex');
const randomHex = (bytes) => crypto.randomBytes(bytes).toString('hex');

/** Comparação em tempo constante (evita descobrir segredos pelo tempo de resposta). */
function safeEqual(a, b) {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  return bufA.length === bufB.length && crypto.timingSafeEqual(bufA, bufB);
}

module.exports = { sha256, randomHex, safeEqual };
