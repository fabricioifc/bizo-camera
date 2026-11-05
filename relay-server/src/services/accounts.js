'use strict';

const totp = require('../lib/totp');
const { randomHex, sha256 } = require('../lib/crypto');

const MAX_CODE_FAILURES = 5;
const LOCKOUT_MS = 15 * 60 * 1000;
const RECOVERY_CODE_COUNT = 8;

const normalizeEmail = (value) => String(value || '').trim().toLowerCase();

/**
 * Contas de usuário: cadastro pelo Google, verificação em duas etapas e bloqueio
 * depois de muitos códigos errados. Os usuários ficam em store.data.users, indexados pelo e-mail.
 */
function createAccounts({ store, allowSignup, issuer }) {
  const users = () => store.data.users;

  function newRecoveryCodes() {
    const codes = [];
    for (let i = 0; i < RECOVERY_CODE_COUNT; i++) {
      const raw = randomHex(4);
      codes.push(`${raw.slice(0, 4)}-${raw.slice(4)}`);
    }
    return codes;
  }

  return {
    /** O primeiro usuário sempre pode se cadastrar; os demais precisam de ALLOW_SIGNUP=1. */
    signupOpen() {
      return allowSignup || Object.keys(users()).length === 0;
    },

    /** { email, user } do usuário com esse id, ou null. */
    byId(id) {
      const entry = Object.entries(users()).find(([, user]) => user.id === id);
      return entry ? { email: entry[0], user: entry[1] } : null;
    },

    byEmail(email) {
      return users()[email] || null;
    },

    /**
     * Conta do Google: vincula a uma conta com o mesmo e-mail (confirmado pelo Google) ou cria uma nova.
     * Devolve { email, user }, ou null se o cadastro estiver fechado.
     */
    resolveGoogle(claims) {
      const email = normalizeEmail(claims.email);
      let entry = Object.entries(users()).find(([, user]) => user.googleId === claims.sub);
      if (!entry && users()[email]) {
        users()[email].googleId = claims.sub;
        entry = [email, users()[email]];
      }
      if (!entry) {
        if (!this.signupOpen()) return null;
        const user = { id: randomHex(8), googleId: claims.sub, createdAt: Date.now(), failures: 0, lockedUntil: 0 };
        users()[email] = user;
        entry = [email, user];
      }
      store.save();
      return { email: entry[0], user: entry[1] };
    },

    // ----- Verificação em duas etapas -----

    twoFactorEnabled(user) {
      return Boolean(user.totp && user.totp.enabled);
    },

    /** Gera um segredo novo (ainda não ativo) e devolve o segredo e a URI otpauth para o QR code. */
    startTwoFactor({ email, user }) {
      const secret = totp.generateSecret();
      user.totp = { secret, enabled: false, lastStep: -1, recovery: [] };
      store.save();
      return { secret, otpauth: totp.otpauthUri(secret, email, issuer) };
    },

    /** Ativa o 2FA se o código bater. Devolve os códigos de recuperação, ou null se o código estiver errado. */
    enableTwoFactor(user, code) {
      const factor = user.totp;
      const step = totp.matchTotp(factor.secret, code, -1);
      if (step === null) return null;
      const codes = newRecoveryCodes();
      factor.enabled = true;
      factor.lastStep = step;
      factor.recovery = codes.map((item) => sha256(item.replace(/-/g, '')));
      store.save();
      return codes;
    },

    disableTwoFactor(user) {
      delete user.totp;
      store.save();
    },

    /** Confere um código do app autenticador ou um código de recuperação (uso único). */
    checkSecondFactor(user, code) {
      const factor = user.totp;
      const step = totp.matchTotp(factor.secret, code, factor.lastStep ?? -1);
      if (step !== null) {
        factor.lastStep = step;
        store.save();
        return true;
      }
      const recovery = String(code || '').toLowerCase().replace(/[^a-f0-9]/g, '');
      const index = factor.recovery.indexOf(sha256(recovery));
      if (recovery && index !== -1) {
        factor.recovery.splice(index, 1);
        store.save();
        return true;
      }
      return false;
    },

    // ----- Bloqueio por códigos errados -----

    isLocked(user) {
      return Boolean(user.lockedUntil && user.lockedUntil > Date.now());
    },

    recordFailure(user) {
      user.failures = (user.failures || 0) + 1;
      if (user.failures >= MAX_CODE_FAILURES) {
        user.lockedUntil = Date.now() + LOCKOUT_MS;
        user.failures = 0;
      }
      store.save();
    },

    resetFailures(user) {
      if (user.failures || user.lockedUntil) {
        user.failures = 0;
        user.lockedUntil = 0;
        store.save();
      }
    },
  };
}

module.exports = { createAccounts };
