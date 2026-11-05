'use strict';

const http = require('node:http');
const { Store } = require('./lib/store');
const { setupPush } = require('./lib/webpush');
const { createRouter } = require('./http/router');
const { createRateLimiter } = require('./http/rate-limit');
const { createStaticHandler } = require('./http/static');
const { requestToken, sendJson } = require('./http/respond');
const { createSessions } = require('./services/sessions');
const { createAccounts } = require('./services/accounts');
const { createMfaChallenges } = require('./services/mfa');
const { createHub } = require('./services/hub');
const { createAlerts } = require('./services/alerts');
const { createDevices } = require('./services/devices');
const { createReleases } = require('./services/releases');
const { createWatchdog } = require('./services/watchdog');
const { authRoutes } = require('./routes/auth');
const { accountRoutes } = require('./routes/account');
const { deviceRoutes } = require('./routes/devices');
const { pushRoutes } = require('./routes/push');
const { appReleaseRoutes } = require('./routes/app-release');
const { attachWebSockets } = require('./websocket');

const SWEEP_MS = 10 * 60 * 1000;

/** Monta o servidor: banco, serviços, rotas HTTP e WebSockets. Não abre a porta. */
function createApp(config) {
  const store = new Store(config.dataFile);
  const push = setupPush({ dataDir: config.dataDir, publicUrl: config.publicUrl });
  const hub = createHub();
  const accounts = createAccounts({ store, allowSignup: config.allowSignup, issuer: config.issuer });
  const alerts = createAlerts({ store, push });
  const releases = createReleases({ dataDir: config.dataDir, appPackage: config.appPackage });
  const app = {
    config,
    store,
    hub,
    accounts,
    alerts,
    sessions: createSessions({ store, secureCookies: config.secureCookies }),
    mfa: createMfaChallenges({ accounts }),
    releases,
    devices: createDevices({
      store, hub, alerts, releases, snapshotDir: config.snapshotDir, clipStorageBytes: config.clipStorageBytes,
    }),
    rateLimit: createRateLimiter({ trustProxy: config.trustProxy }),
  };

  // Alertas de câmera offline, fora da tomada, bateria baixa e calor.
  const watchdog = createWatchdog({ store, hub, alerts, offlineAfterMs: config.offlineAlertMs });
  watchdog.start();

  const router = createRouter();
  const auth = authRoutes(router, app);
  accountRoutes(router, app);
  deviceRoutes(router, app);
  pushRoutes(router, app);
  appReleaseRoutes(router, app);
  const serveStatic = createStaticHandler(config.webDir);

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    try {
      if (url.pathname === '/healthz') {
        // Usado pelo healthcheck do Docker: responde se o processo está atendendo requisições.
        return sendJson(res, 200, { ok: true, cameras: hub.onlineCount() });
      }
      if (!url.pathname.startsWith('/api/')) return serveStatic(url.pathname, res);
      const route = router.match(req.method, url.pathname);
      if (!route) return sendJson(res, 404, { error: 'not_found' });
      await route.handler({ req, res, url, params: route.params, token: requestToken(req), app });
    } catch {
      // Corpo inválido ou grande demais.
      if (!res.headersSent) sendJson(res, 400, { error: 'bad_request' });
    }
  });

  attachWebSockets(server, app);

  // Limpeza periódica de sessões, desafios e tentativas expiradas.
  const sweeper = setInterval(() => {
    const now = Date.now();
    app.sessions.sweep(now);
    app.mfa.sweep(now);
    auth.sweep(now);
    app.rateLimit.sweep(now);
  }, SWEEP_MS);
  sweeper.unref();

  return { server, ...app };
}

module.exports = { createApp };
