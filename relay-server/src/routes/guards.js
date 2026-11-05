'use strict';

const { sendJson } = require('../http/respond');

/** Usuário dono da sessão (pendente ou não). Responde 401 e devolve null se não houver. */
function requireUser(ctx) {
  const userId = ctx.app.sessions.userId(ctx.token);
  if (!userId) sendJson(ctx.res, 401, { error: 'unauthorized' });
  return userId;
}

/** Como requireUser, devolvendo { email, user }. */
function requireAccount(ctx) {
  const userId = ctx.app.sessions.userId(ctx.token);
  const entry = userId ? ctx.app.accounts.byId(userId) : null;
  if (!entry) sendJson(ctx.res, 401, { error: 'unauthorized' });
  return entry;
}

/** Usuário com o 2FA concluído: sessões pendentes recebem 403 até configurar o 2FA. */
function requireFullSession(ctx) {
  const userId = requireUser(ctx);
  if (!userId) return null;
  if (ctx.app.config.require2fa && ctx.app.sessions.isPending(ctx.token)) {
    sendJson(ctx.res, 403, { error: 'two_factor_required' });
    return null;
  }
  return userId;
}

module.exports = { requireUser, requireAccount, requireFullSession };
