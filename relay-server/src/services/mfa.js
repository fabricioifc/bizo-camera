'use strict';

const { randomHex, sha256 } = require('../lib/crypto');

const MFA_TTL_MS = 5 * 60 * 1000;
const MAX_MFA_ATTEMPTS = 5;

/**
 * Segunda etapa do login: depois do Google, quem tem 2FA recebe um desafio temporário
 * (guardado só em memória) e precisa confirmar com um código.
 */
function createMfaChallenges({ accounts }) {
  const pending = new Map(); // sha256(token) -> { email, expiresAt, attempts }

  return {
    create(email) {
      const token = randomHex(32);
      pending.set(sha256(token), { email, expiresAt: Date.now() + MFA_TTL_MS, attempts: 0 });
      return token;
    },

    /**
     * Confere o código do desafio. Devolve { email, user } se deu certo,
     * ou { error: 'mfa_expired' | 'invalid_code' }.
     */
    verify(token, code) {
      const key = typeof token === 'string' ? sha256(token) : '';
      const challenge = pending.get(key);
      const expire = () => {
        pending.delete(key);
        return { error: 'mfa_expired' };
      };
      if (!challenge || challenge.expiresAt < Date.now()) return expire();
      challenge.attempts += 1;
      if (challenge.attempts > MAX_MFA_ATTEMPTS) return expire();
      const user = accounts.byEmail(challenge.email);
      if (!user || !accounts.twoFactorEnabled(user) || accounts.isLocked(user)) return expire();
      if (!accounts.checkSecondFactor(user, code)) {
        accounts.recordFailure(user);
        return { error: 'invalid_code' };
      }
      pending.delete(key);
      accounts.resetFailures(user);
      return { email: challenge.email, user };
    },

    sweep(now = Date.now()) {
      for (const [key, challenge] of pending) if (challenge.expiresAt < now) pending.delete(key);
    },
  };
}

module.exports = { createMfaChallenges };
