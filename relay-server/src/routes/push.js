'use strict';

const { readJson, sendJson } = require('../http/respond');
const { requireFullSession } = require('./guards');

/** Alertas de movimento (Web Push): chave pública e assinaturas deste navegador. */
function pushRoutes(router, app) {
  const { alerts } = app;

  router.get('/api/push/key', ({ res }) => sendJson(res, 200, { publicKey: alerts.publicKey }));

  router.post('/api/push/subscribe', async (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    if (!alerts.subscribe(userId, await readJson(ctx.req))) {
      return sendJson(ctx.res, 400, { error: 'invalid_subscription' });
    }
    return sendJson(ctx.res, 201, { ok: true });
  });

  router.post('/api/push/unsubscribe', async (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    const body = await readJson(ctx.req);
    alerts.unsubscribe(userId, body.endpoint);
    return sendJson(ctx.res, 200, { ok: true });
  });
}

module.exports = { pushRoutes };
