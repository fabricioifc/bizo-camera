'use strict';

const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');
const { WebSocketServer } = require('ws');
const QRCode = require('qrcode');
const { Store } = require('./store');
const totp = require('./totp');
const google = require('./google');

const PORT = Number(process.env.PORT || 8787);
const PUBLIC_URL = (process.env.PUBLIC_URL || `http://localhost:${PORT}`).replace(/\/+$/, '');
const ALLOW_SIGNUP = process.env.ALLOW_SIGNUP === '1';
const TRUST_PROXY = process.env.TRUST_PROXY === '1';
const GOOGLE_CLIENT_ID = process.env.GOOGLE_CLIENT_ID || '';
const GOOGLE_CLIENT_SECRET = process.env.GOOGLE_CLIENT_SECRET || '';
const GOOGLE_ENABLED = Boolean(GOOGLE_CLIENT_ID && GOOGLE_CLIENT_SECRET);
// Cookie "Secure" só quando o site é HTTPS; em teste local (http) o navegador ignoraria o cookie.
const SECURE_COOKIES = PUBLIC_URL.startsWith('https://');
const APP_SCHEME = 'monitor://auth';
const ISSUER = 'Monitor de Câmera';

const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MFA_TTL_MS = 5 * 60 * 1000;
const OAUTH_TTL_MS = 10 * 60 * 1000;
const APP_CODE_TTL_MS = 60 * 1000;
const MAX_MFA_ATTEMPTS = 5;
const MAX_CODE_FAILURES = 5;
const LOCKOUT_MS = 15 * 60 * 1000;
const RECOVERY_CODE_COUNT = 8;
const MAX_BUFFERED_BYTES = 2 * 1024 * 1024;
const OPEN = 1;
const WEB_DIR = path.join(__dirname, '..', 'web');

const STATIC_FILES = new Map([
  ['/', { file: 'index.html', type: 'text/html; charset=utf-8' }],
  ['/app.js', { file: 'app.js', type: 'text/javascript; charset=utf-8' }],
  ['/styles.css', { file: 'styles.css', type: 'text/css; charset=utf-8' }],
]);

const store = new Store(process.env.DATA_FILE || path.join(__dirname, '..', 'data', 'db.json'));
const deviceSockets = new Map(); // deviceId -> WebSocket da câmera
const viewerSockets = new Map(); // deviceId -> Set<WebSocket> dos visualizadores
const rateBuckets = new Map(); // chave -> { count, resetAt }
const pendingMfa = new Map(); // sha256(mfaToken) -> { email, expiresAt, attempts }
const pendingOauth = new Map(); // state -> { verifier, nonce, app, expiresAt }
const appCodes = new Map(); // sha256(code) -> { userId, expiresAt }; troca única por token do app

const sha256 = (value) => crypto.createHash('sha256').update(value).digest('hex');
const randomHex = (bytes) => crypto.randomBytes(bytes).toString('hex');

function safeEqual(a, b) {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  return bufA.length === bufB.length && crypto.timingSafeEqual(bufA, bufB);
}

function normalizeEmail(value) {
  return String(value || '').trim().toLowerCase();
}

function signupOpen() {
  // O primeiro usuário sempre pode entrar pela primeira vez; os demais precisam de ALLOW_SIGNUP=1.
  return ALLOW_SIGNUP || Object.keys(store.data.users).length === 0;
}

function rateLimited(req, key, max, windowMs = 60_000) {
  const forwarded = TRUST_PROXY ? req.headers['x-forwarded-for'] : undefined;
  const ip = (forwarded ? forwarded.split(',')[0].trim() : req.socket.remoteAddress) || 'unknown';
  const bucketKey = `${key}:${ip}`;
  const now = Date.now();
  let bucket = rateBuckets.get(bucketKey);
  if (!bucket || bucket.resetAt < now) {
    bucket = { count: 0, resetAt: now + windowMs };
    rateBuckets.set(bucketKey, bucket);
  }
  bucket.count += 1;
  return bucket.count > max;
}

function sendJson(res, status, body, headers = {}) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    ...headers,
  });
  res.end(JSON.stringify(body));
}

function redirect(res, location, headers = {}) {
  res.writeHead(302, { Location: location, 'Cache-Control': 'no-store', ...headers });
  res.end();
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > 10_000) {
        req.destroy();
        reject(new Error('payload too large'));
      }
    });
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (err) {
        reject(err);
      }
    });
    req.on('error', reject);
  });
}

function bearerToken(req) {
  const header = req.headers.authorization || '';
  return header.startsWith('Bearer ') ? header.slice(7) : null;
}

// Cookie de sessão: a página web não guarda o token em JavaScript. O app usa o Bearer.
function cookieToken(req) {
  const header = req.headers.cookie || '';
  for (const part of header.split(';')) {
    const [name, ...rest] = part.trim().split('=');
    if (name === 'session') return decodeURIComponent(rest.join('='));
  }
  return null;
}

function requestToken(req) {
  return bearerToken(req) || cookieToken(req);
}

function sessionCookie(token) {
  const parts = [
    `session=${encodeURIComponent(token)}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Strict',
    `Max-Age=${SESSION_TTL_MS / 1000}`,
  ];
  if (SECURE_COOKIES) parts.push('Secure');
  return parts.join('; ');
}

function clearedSessionCookie() {
  const parts = ['session=', 'Path=/', 'HttpOnly', 'SameSite=Strict', 'Max-Age=0'];
  if (SECURE_COOKIES) parts.push('Secure');
  return parts.join('; ');
}

// pending: login pelo Google sem 2FA ativo. Essa sessão só serve para configurar o 2FA.
function createSession(userId, pending = false) {
  const token = randomHex(32);
  store.data.sessions[sha256(token)] = { userId, expiresAt: Date.now() + SESSION_TTL_MS, pending };
  store.save();
  return token;
}

function sessionPending(token) {
  const session = token ? store.data.sessions[sha256(token)] : undefined;
  return Boolean(session && session.pending);
}

// Retorna o id do usuário dono do token, ou null se o token não existe ou expirou.
function sessionUserId(token) {
  if (!token) return null;
  const key = sha256(token);
  const session = store.data.sessions[key];
  if (!session) return null;
  if (session.expiresAt < Date.now()) {
    delete store.data.sessions[key];
    store.save();
    return null;
  }
  return session.userId;
}

function userById(id) {
  const entry = Object.entries(store.data.users).find(([, user]) => user.id === id);
  return entry ? { email: entry[0], user: entry[1] } : null;
}

// Retorna o id da câmera se o segredo estiver correto.
function authenticateDevice(deviceId, secret) {
  const device = deviceId ? store.data.devices[deviceId] : undefined;
  return device && secret && safeEqual(sha256(secret), device.secretHash) ? deviceId : null;
}

function deviceView(id) {
  const device = store.data.devices[id];
  return { id, name: device.name, online: deviceSockets.has(id) };
}

// Contagem de códigos 2FA errados: bloqueia a conta por um tempo depois de várias tentativas.
function recordCodeFailure(user) {
  user.failures = (user.failures || 0) + 1;
  if (user.failures >= MAX_CODE_FAILURES) {
    user.lockedUntil = Date.now() + LOCKOUT_MS;
    user.failures = 0;
  }
  store.save();
}

function isLocked(user) {
  return Boolean(user.lockedUntil && user.lockedUntil > Date.now());
}

function resetFailures(user) {
  if (user.failures || user.lockedUntil) {
    user.failures = 0;
    user.lockedUntil = 0;
    store.save();
  }
}

function twoFactorEnabled(user) {
  return Boolean(user.totp && user.totp.enabled);
}

// Confere um código do app autenticador ou um código de recuperação (uso único).
function checkSecondFactor(user, code) {
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
}

function newRecoveryCodes() {
  const codes = [];
  for (let i = 0; i < RECOVERY_CODE_COUNT; i++) {
    const raw = randomHex(4);
    codes.push(`${raw.slice(0, 4)}-${raw.slice(4)}`);
  }
  return codes;
}

function createMfaChallenge(email) {
  const token = randomHex(32);
  pendingMfa.set(sha256(token), { email, expiresAt: Date.now() + MFA_TTL_MS, attempts: 0 });
  return token;
}

// Código de uso único, trocado pelo app por um token de sessão próprio.
function createAppCode(userId) {
  const code = randomHex(24);
  appCodes.set(sha256(code), { userId, expiresAt: Date.now() + APP_CODE_TTL_MS });
  return code;
}

async function handleApi(req, res, url) {
  const route = `${req.method} ${url.pathname}`;
  const token = requestToken(req);

  if (route === 'GET /api/config') {
    return sendJson(res, 200, { signupOpen: signupOpen(), googleEnabled: GOOGLE_ENABLED });
  }

  // Segunda etapa do login: o código do app autenticador ou um código de recuperação.
  if (route === 'POST /api/auth/mfa') {
    if (rateLimited(req, 'mfa', 10)) return sendJson(res, 429, { error: 'too_many_requests' });
    const body = await readJson(req);
    const key = typeof body.mfaToken === 'string' ? sha256(body.mfaToken) : '';
    const challenge = pendingMfa.get(key);
    if (!challenge || challenge.expiresAt < Date.now()) {
      pendingMfa.delete(key);
      return sendJson(res, 401, { error: 'mfa_expired' });
    }
    challenge.attempts += 1;
    if (challenge.attempts > MAX_MFA_ATTEMPTS) {
      pendingMfa.delete(key);
      return sendJson(res, 401, { error: 'mfa_expired' });
    }
    const entry = store.data.users[challenge.email];
    if (!entry || !twoFactorEnabled(entry) || isLocked(entry)) {
      pendingMfa.delete(key);
      return sendJson(res, 401, { error: 'mfa_expired' });
    }
    if (!checkSecondFactor(entry, body.code)) {
      recordCodeFailure(entry);
      return sendJson(res, 401, { error: 'invalid_code' });
    }
    pendingMfa.delete(key);
    resetFailures(entry);
    const session = createSession(entry.id);
    return sendJson(res, 200, { token: session, email: challenge.email }, { 'Set-Cookie': sessionCookie(session) });
  }

  // A página web, já logada, pede um código para entregar a sessão ao app Android.
  if (route === 'POST /api/auth/app-code') {
    const userId = sessionUserId(token);
    if (!userId) return sendJson(res, 401, { error: 'unauthorized' });
    if (sessionPending(token)) return sendJson(res, 403, { error: 'two_factor_required' });
    return sendJson(res, 200, { code: createAppCode(userId) });
  }

  // O app troca o código de uso único por um token de sessão próprio.
  if (route === 'POST /api/auth/app-token') {
    if (rateLimited(req, 'app-token', 20)) return sendJson(res, 429, { error: 'too_many_requests' });
    const body = await readJson(req);
    const key = typeof body.code === 'string' ? sha256(body.code) : '';
    const grant = appCodes.get(key);
    appCodes.delete(key);
    if (!grant || grant.expiresAt < Date.now()) return sendJson(res, 401, { error: 'code_expired' });
    const entry = userById(grant.userId);
    if (!entry) return sendJson(res, 401, { error: 'unauthorized' });
    return sendJson(res, 200, { token: createSession(grant.userId), email: entry.email });
  }

  if (route === 'POST /api/auth/logout') {
    if (token) {
      delete store.data.sessions[sha256(token)];
      store.save();
    }
    return sendJson(res, 200, { ok: true }, { 'Set-Cookie': clearedSessionCookie() });
  }

  if (route === 'GET /api/auth/me') {
    const userId = sessionUserId(token);
    const entry = userId ? userById(userId) : null;
    if (!entry) return sendJson(res, 401, { error: 'unauthorized' });
    return sendJson(res, 200, {
      email: entry.email,
      google: Boolean(entry.user.googleId),
      twoFactor: twoFactorEnabled(entry.user),
      pending: sessionPending(token),
    });
  }

  if (route === 'POST /api/account/2fa/setup') {
    const entry = userById(sessionUserId(token) || '');
    if (!entry) return sendJson(res, 401, { error: 'unauthorized' });
    if (twoFactorEnabled(entry.user)) return sendJson(res, 409, { error: 'already_enabled' });
    const secret = totp.generateSecret();
    const otpauth = totp.otpauthUri(secret, entry.email, ISSUER);
    entry.user.totp = { secret, enabled: false, lastStep: -1, recovery: [] };
    store.save();
    return sendJson(res, 200, { secret, otpauth, qr: await QRCode.toDataURL(otpauth, { margin: 1, width: 220 }) });
  }

  if (route === 'POST /api/account/2fa/enable') {
    const entry = userById(sessionUserId(token) || '');
    if (!entry) return sendJson(res, 401, { error: 'unauthorized' });
    const factor = entry.user.totp;
    if (!factor || factor.enabled) return sendJson(res, 400, { error: 'setup_required' });
    const body = await readJson(req);
    const step = totp.matchTotp(factor.secret, body.code, -1);
    if (step === null) return sendJson(res, 400, { error: 'invalid_code' });
    const codes = newRecoveryCodes();
    factor.enabled = true;
    factor.lastStep = step;
    factor.recovery = codes.map((code) => sha256(code.replace(/-/g, '')));
    // Com o 2FA ativo, a sessão pendente vira uma sessão completa.
    const session = store.data.sessions[sha256(token)];
    if (session) session.pending = false;
    store.save();
    return sendJson(res, 200, { recoveryCodes: codes });
  }

  if (route === 'POST /api/account/2fa/disable') {
    const entry = userById(sessionUserId(token) || '');
    if (!entry) return sendJson(res, 401, { error: 'unauthorized' });
    if (!twoFactorEnabled(entry.user)) return sendJson(res, 400, { error: 'not_enabled' });
    const body = await readJson(req);
    if (!checkSecondFactor(entry.user, body.code)) return sendJson(res, 401, { error: 'invalid_code' });
    delete entry.user.totp;
    store.save();
    return sendJson(res, 200, { ok: true });
  }

  if (route === 'GET /api/devices') {
    const userId = sessionUserId(token);
    if (!userId) return sendJson(res, 401, { error: 'unauthorized' });
    if (sessionPending(token)) return sendJson(res, 403, { error: 'two_factor_required' });
    const devices = Object.entries(store.data.devices)
      .filter(([, device]) => device.ownerId === userId)
      .map(([id]) => deviceView(id));
    return sendJson(res, 200, { devices });
  }

  // A câmera faz login uma vez e registra o aparelho na conta.
  if (route === 'POST /api/devices/register') {
    const userId = sessionUserId(token);
    if (!userId) return sendJson(res, 401, { error: 'unauthorized' });
    if (sessionPending(token)) return sendJson(res, 403, { error: 'two_factor_required' });
    const body = await readJson(req);
    const id = randomHex(8);
    const secret = randomHex(32);
    store.data.devices[id] = {
      ownerId: userId,
      name: String(body.name || 'Câmera').slice(0, 40),
      secretHash: sha256(secret),
      createdAt: Date.now(),
    };
    store.save();
    return sendJson(res, 201, { deviceId: id, deviceSecret: secret });
  }

  return sendJson(res, 404, { error: 'not_found' });
}

// Login com Google: inicia o fluxo e recebe o retorno do Google.
// Com app=1, o retorno vai para o app Android (web ou link monitor://).
function handleGoogle(req, res, url) {
  if (!GOOGLE_ENABLED) return sendJson(res, 404, { error: 'not_found' });
  const redirectUri = `${PUBLIC_URL}/api/auth/google/callback`;

  if (url.pathname === '/api/auth/google/start') {
    if (rateLimited(req, 'google', 20)) return sendJson(res, 429, { error: 'too_many_requests' });
    const attempt = google.newAttempt();
    pendingOauth.set(attempt.state, {
      verifier: attempt.verifier,
      nonce: attempt.nonce,
      app: url.searchParams.get('app') === '1',
      expiresAt: Date.now() + OAUTH_TTL_MS,
    });
    return redirect(res, google.authorizationUrl({ clientId: GOOGLE_CLIENT_ID, redirectUri, attempt }));
  }

  // url.pathname === '/api/auth/google/callback'
  const state = url.searchParams.get('state') || '';
  const attempt = pendingOauth.get(state);
  pendingOauth.delete(state);
  const code = url.searchParams.get('code');
  if (!attempt || attempt.expiresAt < Date.now() || !code || url.searchParams.get('error')) {
    return redirect(res, failureTarget(attempt, 'error=google'));
  }

  return finishGoogleLogin(res, { code, attempt, redirectUri }).catch(() => {
    if (!res.headersSent) redirect(res, failureTarget(attempt, 'error=google'));
  });
}

// Destino de erro: o app recebe pelo link monitor://, a web pelo fragmento.
function failureTarget(attempt, reason) {
  if (attempt && attempt.app) return `${APP_SCHEME}?error=${reason.replace('error=', '')}`;
  return `/#${reason}`;
}

async function finishGoogleLogin(res, { code, attempt, redirectUri }) {
  const idToken = await google.exchangeCode({
    clientId: GOOGLE_CLIENT_ID,
    clientSecret: GOOGLE_CLIENT_SECRET,
    redirectUri,
    code,
    verifier: attempt.verifier,
  });
  const claims = await google.verifyIdToken(idToken, { clientId: GOOGLE_CLIENT_ID, nonce: attempt.nonce });
  const email = normalizeEmail(claims.email);

  // Conta já vinculada ao Google, ou conta com o mesmo e-mail (confirmado pelo Google).
  let entry = Object.entries(store.data.users).find(([, user]) => user.googleId === claims.sub);
  if (!entry && store.data.users[email]) {
    store.data.users[email].googleId = claims.sub;
    entry = [email, store.data.users[email]];
  }
  if (!entry) {
    if (!signupOpen()) return redirect(res, failureTarget(attempt, 'error=signup_disabled'));
    const user = { id: randomHex(8), googleId: claims.sub, createdAt: Date.now(), failures: 0, lockedUntil: 0 };
    store.data.users[email] = user;
    entry = [email, user];
  }
  store.save();

  const [accountEmail, user] = entry;
  const base = attempt.app ? '/?app=1' : '/';
  if (twoFactorEnabled(user)) {
    // O 2FA da conta continua valendo depois do Google. O código vai no fragmento da URL,
    // que o navegador não envia ao servidor.
    return redirect(res, `${base}#mfa=${createMfaChallenge(accountEmail)}`);
  }
  // Login pelo Google exige 2FA. Sem 2FA ativo, a sessão fica pendente até a configuração.
  const token = createSession(user.id, !twoFactorEnabled(user));
  return redirect(res, base, { 'Set-Cookie': sessionCookie(token) });
}

function serveStatic(pathname, res) {
  const entry = STATIC_FILES.get(pathname);
  if (!entry) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('Não encontrado');
  }
  fs.readFile(path.join(WEB_DIR, entry.file), (err, data) => {
    if (err) {
      res.writeHead(500);
      return res.end();
    }
    res.writeHead(200, {
      'Content-Type': entry.type,
      'Cache-Control': 'no-cache',
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'no-referrer',
      'Content-Security-Policy': "default-src 'self'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'",
    });
    res.end(data);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (url.pathname.startsWith('/api/auth/google/')) {
      handleGoogle(req, res, url);
    } else if (url.pathname.startsWith('/api/')) {
      await handleApi(req, res, url);
    } else {
      serveStatic(url.pathname, res);
    }
  } catch (err) {
    if (!res.headersSent) sendJson(res, 400, { error: 'bad_request' });
  }
});

function sendJsonWs(ws, payload) {
  if (ws && ws.readyState === OPEN) ws.send(JSON.stringify(payload));
}

function notifyViewers(deviceId, payload) {
  for (const viewer of viewerSockets.get(deviceId) || []) sendJsonWs(viewer, payload);
}

// Câmera conectada: envia "start" se já houver visualizadores e repassa os quadros.
function attachDevice(deviceId, ws) {
  const previous = deviceSockets.get(deviceId);
  if (previous) previous.terminate();
  deviceSockets.set(deviceId, ws);
  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (data, isBinary) => {
    if (!isBinary) return;
    for (const viewer of viewerSockets.get(deviceId) || []) {
      // Descarta quadros para quem está com a conexão lenta, em vez de acumular atraso.
      if (viewer.readyState === OPEN && viewer.bufferedAmount < MAX_BUFFERED_BYTES) {
        viewer.send(data, { binary: true });
      }
    }
  });

  ws.on('close', () => {
    if (deviceSockets.get(deviceId) === ws) {
      deviceSockets.delete(deviceId);
      notifyViewers(deviceId, { type: 'status', online: false });
    }
  });

  notifyViewers(deviceId, { type: 'status', online: true });
  if (viewerSockets.get(deviceId)?.size) sendJsonWs(ws, { type: 'start' });
}

// Visualizador: a primeira conexão faz a câmera começar a transmitir.
function attachViewer(deviceId, ws) {
  let viewers = viewerSockets.get(deviceId);
  if (!viewers) {
    viewers = new Set();
    viewerSockets.set(deviceId, viewers);
  }
  const wasIdle = viewers.size === 0;
  viewers.add(ws);
  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });

  sendJsonWs(ws, { type: 'status', online: deviceSockets.has(deviceId) });
  if (wasIdle) sendJsonWs(deviceSockets.get(deviceId), { type: 'start' });

  ws.on('close', () => {
    viewers.delete(ws);
    if (viewers.size === 0) {
      viewerSockets.delete(deviceId);
      sendJsonWs(deviceSockets.get(deviceId), { type: 'stop' });
    }
  });
}

const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });

function reject(socket, status) {
  socket.write(`HTTP/1.1 ${status}\r\nConnection: close\r\n\r\n`);
  socket.destroy();
}

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');

  if (url.pathname === '/ws/device') {
    const deviceId = authenticateDevice(url.searchParams.get('id'), bearerToken(req));
    if (!deviceId) return reject(socket, '401 Unauthorized');
    return wss.handleUpgrade(req, socket, head, (ws) => attachDevice(deviceId, ws));
  }

  if (url.pathname === '/ws/view') {
    const deviceId = url.searchParams.get('device');
    // A página web envia o cookie; o token na URL continua aceito por compatibilidade.
    const sessionToken = url.searchParams.get('token') || cookieToken(req);
    const userId = sessionUserId(sessionToken);
    const device = deviceId ? store.data.devices[deviceId] : undefined;
    if (!userId || sessionPending(sessionToken) || !device || device.ownerId !== userId) {
      return reject(socket, '403 Forbidden');
    }
    return wss.handleUpgrade(req, socket, head, (ws) => attachViewer(deviceId, ws));
  }

  return reject(socket, '404 Not Found');
});

// Detecta conexões mortas (queda de Wi-Fi/LTE sem aviso).
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 30_000);

// Limpeza periódica de sessões, desafios e tentativas expiradas.
setInterval(() => {
  const now = Date.now();
  let changed = false;
  for (const [key, session] of Object.entries(store.data.sessions)) {
    if (session.expiresAt < now) {
      delete store.data.sessions[key];
      changed = true;
    }
  }
  if (changed) store.save();
  for (const [key, challenge] of pendingMfa) if (challenge.expiresAt < now) pendingMfa.delete(key);
  for (const [key, attempt] of pendingOauth) if (attempt.expiresAt < now) pendingOauth.delete(key);
  for (const [key, grant] of appCodes) if (grant.expiresAt < now) appCodes.delete(key);
  for (const [key, bucket] of rateBuckets) if (bucket.resetAt < now) rateBuckets.delete(key);
}, 10 * 60 * 1000);

server.listen(PORT, () => {
  console.log(`Relay ouvindo na porta ${PORT} (${PUBLIC_URL})`);
  console.log(signupOpen() ? 'Cadastro aberto.' : 'Cadastro fechado (use ALLOW_SIGNUP=1 para abrir).');
  console.log(GOOGLE_ENABLED ? 'Login com Google ativo.' : 'Login com Google desativado (GOOGLE_CLIENT_ID/SECRET ausentes).');
});

module.exports = { server };
