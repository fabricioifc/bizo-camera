'use strict';

const { randomHex, sha256 } = require('../lib/crypto');

const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * Sessões de login. O banco guarda só o sha256 do token.
 * pending: login pelo Google sem 2FA ativo; essa sessão só serve para configurar o 2FA.
 */
function createSessions({ store, secureCookies }) {
  const find = (token) => (token ? store.data.sessions[sha256(token)] : undefined);

  function cookieParts(value, maxAgeSeconds) {
    const parts = [`session=${value}`, 'Path=/', 'HttpOnly', 'SameSite=Strict', `Max-Age=${maxAgeSeconds}`];
    if (secureCookies) parts.push('Secure');
    return parts.join('; ');
  }

  return {
    create(userId, pending = false) {
      const token = randomHex(32);
      store.data.sessions[sha256(token)] = { userId, expiresAt: Date.now() + SESSION_TTL_MS, pending };
      store.save();
      return token;
    },

    /** Id do usuário dono do token, ou null se o token não existe ou expirou. */
    userId(token) {
      const session = find(token);
      if (!session) return null;
      if (session.expiresAt < Date.now()) {
        delete store.data.sessions[sha256(token)];
        store.save();
        return null;
      }
      return session.userId;
    },

    isPending(token) {
      return Boolean(find(token)?.pending);
    },

    /** Com o 2FA ativo, a sessão pendente vira uma sessão completa. */
    complete(token) {
      const session = find(token);
      if (session) session.pending = false;
    },

    destroy(token) {
      if (!token) return;
      delete store.data.sessions[sha256(token)];
      store.save();
    },

    cookie(token) {
      return cookieParts(encodeURIComponent(token), SESSION_TTL_MS / 1000);
    },

    clearedCookie() {
      return cookieParts('', 0);
    },

    sweep(now = Date.now()) {
      let changed = false;
      for (const [key, session] of Object.entries(store.data.sessions)) {
        if (session.expiresAt < now) {
          delete store.data.sessions[key];
          changed = true;
        }
      }
      if (changed) store.save();
    },
  };
}

module.exports = { createSessions };
