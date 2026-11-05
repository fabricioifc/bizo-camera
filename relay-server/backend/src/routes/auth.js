'use strict';

const google = require('../lib/google');
const { readJson, redirect, sendJson } = require('../http/respond');

const OAUTH_TTL_MS = 10 * 60 * 1000;

/** Login: Google (web e app), segunda etapa (2FA), sessão atual e logout. */
function authRoutes(router, app) {
  const { config, sessions, accounts, mfa, rateLimit } = app;
  const pendingOauth = new Map(); // state -> { verifier, nonce, expiresAt }
  const redirectUri = `${config.publicUrl}/api/auth/google/callback`;

  // Depois do Google: com 2FA, pede o código; sem 2FA, a sessão fica pendente até configurá-lo.
  function afterGoogle({ email, user }) {
    if (config.require2fa && accounts.twoFactorEnabled(user)) return { mfaToken: mfa.create(email) };
    const pending = config.require2fa;
    return { token: sessions.create(user.id, pending), email, pending };
  }

  router.get('/api/config', ({ res }) =>
    sendJson(res, 200, { signupOpen: accounts.signupOpen(), googleEnabled: config.google.enabled }));

  // Segunda etapa do login: o código do app autenticador ou um código de recuperação.
  router.post('/api/auth/mfa', async ({ req, res }) => {
    if (rateLimit.limited(req, 'mfa', 10)) return sendJson(res, 429, { error: 'too_many_requests' });
    const body = await readJson(req);
    const result = mfa.verify(body.mfaToken, body.code);
    if (result.error) return sendJson(res, 401, { error: result.error });
    const token = sessions.create(result.user.id);
    return sendJson(res, 200, { token, email: result.email }, { 'Set-Cookie': sessions.cookie(token) });
  });

  // Login nativo do app: recebe o ID token do Google (obtido pelo Credential Manager).
  router.post('/api/auth/google/app', async ({ req, res }) => {
    if (!config.google.enabled) return sendJson(res, 404, { error: 'not_found' });
    if (rateLimit.limited(req, 'google-app', 20)) return sendJson(res, 429, { error: 'too_many_requests' });
    const body = await readJson(req);
    let claims;
    try {
      claims = await google.verifyIdToken(String(body.idToken || ''), { clientId: config.google.clientId });
    } catch {
      return sendJson(res, 401, { error: 'invalid_google_token' });
    }
    const account = accounts.resolveGoogle(claims);
    if (!account) return sendJson(res, 403, { error: 'signup_disabled' });
    const next = afterGoogle(account);
    if (next.mfaToken) return sendJson(res, 200, { mfaRequired: true, mfaToken: next.mfaToken });
    return sendJson(res, 200, next);
  });

  // Login com Google pela web: redireciona para o Google...
  router.get('/api/auth/google/start', ({ req, res }) => {
    if (!config.google.enabled) return sendJson(res, 404, { error: 'not_found' });
    if (rateLimit.limited(req, 'google', 20)) return sendJson(res, 429, { error: 'too_many_requests' });
    const attempt = google.newAttempt();
    pendingOauth.set(attempt.state, {
      verifier: attempt.verifier,
      nonce: attempt.nonce,
      expiresAt: Date.now() + OAUTH_TTL_MS,
    });
    return redirect(res, google.authorizationUrl({ clientId: config.google.clientId, redirectUri, attempt }));
  });

  // ...e recebe o retorno. Os erros voltam para a página no fragmento da URL.
  router.get('/api/auth/google/callback', async ({ res, url }) => {
    if (!config.google.enabled) return sendJson(res, 404, { error: 'not_found' });
    const state = url.searchParams.get('state') || '';
    const attempt = pendingOauth.get(state);
    pendingOauth.delete(state);
    const code = url.searchParams.get('code');
    if (!attempt || attempt.expiresAt < Date.now() || !code || url.searchParams.get('error')) {
      return redirect(res, '/#error=google');
    }
    try {
      const idToken = await google.exchangeCode({
        clientId: config.google.clientId,
        clientSecret: config.google.clientSecret,
        redirectUri,
        code,
        verifier: attempt.verifier,
      });
      const claims = await google.verifyIdToken(idToken, { clientId: config.google.clientId, nonce: attempt.nonce });
      const account = accounts.resolveGoogle(claims);
      if (!account) return redirect(res, '/#error=signup_disabled');
      const next = afterGoogle(account);
      // O desafio do 2FA vai no fragmento da URL, que o navegador não envia ao servidor.
      if (next.mfaToken) return redirect(res, `/#mfa=${next.mfaToken}`);
      return redirect(res, '/', { 'Set-Cookie': sessions.cookie(next.token) });
    } catch {
      if (!res.headersSent) redirect(res, '/#error=google');
    }
  });

  router.post('/api/auth/logout', ({ res, token }) => {
    sessions.destroy(token);
    return sendJson(res, 200, { ok: true }, { 'Set-Cookie': sessions.clearedCookie() });
  });

  router.get('/api/auth/me', (ctx) => {
    const userId = sessions.userId(ctx.token);
    const entry = userId ? accounts.byId(userId) : null;
    if (!entry) return sendJson(ctx.res, 401, { error: 'unauthorized' });
    return sendJson(ctx.res, 200, {
      email: entry.email,
      google: Boolean(entry.user.googleId),
      twoFactor: accounts.twoFactorEnabled(entry.user),
      pending: config.require2fa && sessions.isPending(ctx.token),
    });
  });

  return {
    sweep(now = Date.now()) {
      for (const [key, attempt] of pendingOauth) if (attempt.expiresAt < now) pendingOauth.delete(key);
    },
  };
}

module.exports = { authRoutes };
