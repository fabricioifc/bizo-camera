'use strict';

const QRCode = require('qrcode');
const { readJson, sendJson } = require('../http/respond');
const { requireAccount } = require('./guards');

/** Verificação em duas etapas da conta. Funciona também com a sessão pendente (é para isso que ela existe). */
function accountRoutes(router, app) {
  const { accounts, sessions } = app;

  router.post('/api/account/2fa/setup', async (ctx) => {
    const entry = requireAccount(ctx);
    if (!entry) return;
    if (accounts.twoFactorEnabled(entry.user)) return sendJson(ctx.res, 409, { error: 'already_enabled' });
    const { secret, otpauth } = accounts.startTwoFactor(entry);
    const qr = await QRCode.toDataURL(otpauth, { margin: 1, width: 220 });
    return sendJson(ctx.res, 200, { secret, otpauth, qr });
  });

  router.post('/api/account/2fa/enable', async (ctx) => {
    const entry = requireAccount(ctx);
    if (!entry) return;
    const factor = entry.user.totp;
    if (!factor || factor.enabled) return sendJson(ctx.res, 400, { error: 'setup_required' });
    const body = await readJson(ctx.req);
    const recoveryCodes = accounts.enableTwoFactor(entry.user, body.code);
    if (!recoveryCodes) return sendJson(ctx.res, 400, { error: 'invalid_code' });
    sessions.complete(ctx.token);
    app.store.save();
    return sendJson(ctx.res, 200, { recoveryCodes });
  });

  router.post('/api/account/2fa/disable', async (ctx) => {
    const entry = requireAccount(ctx);
    if (!entry) return;
    if (!accounts.twoFactorEnabled(entry.user)) return sendJson(ctx.res, 400, { error: 'not_enabled' });
    const body = await readJson(ctx.req);
    if (!accounts.checkSecondFactor(entry.user, body.code)) return sendJson(ctx.res, 401, { error: 'invalid_code' });
    accounts.disableTwoFactor(entry.user);
    return sendJson(ctx.res, 200, { ok: true });
  });
}

module.exports = { accountRoutes };
