'use strict';

const fs = require('node:fs');
const { readBody, sendJson } = require('../http/respond');
const { requireFullSession } = require('./guards');

const MAX_APK_BYTES = 64 * 1024 * 1024;

/**
 * Atualização do app pelo próprio app: a página envia o APK de release;
 * o app consulta a versão publicada e baixa o APK. Tudo exige a sessão da conta.
 */
function appReleaseRoutes(router, app) {
  const { releases } = app;

  // Publica uma versão nova (corpo = o arquivo APK).
  router.post('/api/app/release', async (ctx) => {
    if (!requireFullSession(ctx)) return;
    let apk;
    try {
      apk = await readBody(ctx.req, MAX_APK_BYTES);
    } catch (err) {
      if (err.message === 'too_large') return sendJson(ctx.res, 413, { error: 'too_large' });
      throw err;
    }
    try {
      return sendJson(ctx.res, 201, releases.publish(apk));
    } catch (err) {
      if (err.message === 'invalid_apk' || err.message === 'wrong_package') {
        return sendJson(ctx.res, 400, { error: err.message });
      }
      throw err;
    }
  });

  // Versão publicada: o app compara com a instalada.
  router.get('/api/app/latest', (ctx) => {
    if (!requireFullSession(ctx)) return;
    const latest = releases.latest();
    if (!latest) return sendJson(ctx.res, 404, { error: 'no_release' });
    return sendJson(ctx.res, 200, latest);
  });

  // O APK em si.
  router.get('/api/app/download', (ctx) => {
    if (!requireFullSession(ctx)) return;
    const latest = releases.latest();
    if (!latest || !fs.existsSync(releases.apkPath)) return sendJson(ctx.res, 404, { error: 'no_release' });
    const name = `camera-app-${String(latest.versionName).replace(/[^\w.-]/g, '')}.apk`;
    ctx.res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': fs.statSync(releases.apkPath).size,
      'Content-Disposition': `attachment; filename="${name}"`,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    fs.createReadStream(releases.apkPath).pipe(ctx.res);
  });
}

module.exports = { appReleaseRoutes };
