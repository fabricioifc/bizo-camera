'use strict';

const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');
const { WebSocketServer } = require('ws');
const { Store } = require('./store');

const PORT = Number(process.env.PORT || 8787);
const ALLOW_SIGNUP = process.env.ALLOW_SIGNUP === '1';
const TRUST_PROXY = process.env.TRUST_PROXY === '1';
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_BUFFERED_BYTES = 2 * 1024 * 1024;
const OPEN = 1;
const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
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

const sha256 = (value) => crypto.createHash('sha256').update(value).digest('hex');
const randomHex = (bytes) => crypto.randomBytes(bytes).toString('hex');

function safeEqual(a, b) {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  return bufA.length === bufB.length && crypto.timingSafeEqual(bufA, bufB);
}

function hashPassword(password) {
  const salt = randomHex(16);
  return `${salt}:${crypto.scryptSync(password, salt, 64).toString('hex')}`;
}

function verifyPassword(password, stored) {
  if (!stored) return false;
  const [salt, hash] = stored.split(':');
  return safeEqual(crypto.scryptSync(password, salt, 64).toString('hex'), hash);
}

function normalizeEmail(value) {
  return String(value || '').trim().toLowerCase();
}

function signupOpen() {
  // O primeiro usuário sempre pode se cadastrar; os demais precisam de ALLOW_SIGNUP=1.
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

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
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

function createSession(userId) {
  const token = randomHex(32);
  store.data.sessions[sha256(token)] = { userId, expiresAt: Date.now() + SESSION_TTL_MS };
  store.save();
  return token;
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

// Retorna o id da câmera se o segredo estiver correto.
function authenticateDevice(deviceId, secret) {
  const device = deviceId ? store.data.devices[deviceId] : undefined;
  return device && secret && safeEqual(sha256(secret), device.secretHash) ? deviceId : null;
}

function deviceView(id) {
  const device = store.data.devices[id];
  return { id, name: device.name, online: deviceSockets.has(id) };
}

async function handleApi(req, res, url) {
  const route = `${req.method} ${url.pathname}`;
  const token = bearerToken(req);

  if (route === 'GET /api/config') {
    return sendJson(res, 200, { signupOpen: signupOpen() });
  }

  if (route === 'POST /api/auth/signup') {
    if (rateLimited(req, 'signup', 10)) return sendJson(res, 429, { error: 'too_many_requests' });
    if (!signupOpen()) return sendJson(res, 403, { error: 'signup_disabled' });
    const body = await readJson(req);
    const email = normalizeEmail(body.email);
    if (!EMAIL_RE.test(email)) return sendJson(res, 400, { error: 'invalid_email' });
    if (typeof body.password !== 'string' || body.password.length < 8) {
      return sendJson(res, 400, { error: 'weak_password' });
    }
    if (store.data.users[email]) return sendJson(res, 409, { error: 'email_taken' });

    const id = randomHex(8);
    store.data.users[email] = { id, passwordHash: hashPassword(body.password), createdAt: Date.now() };
    store.save();
    return sendJson(res, 201, { token: createSession(id), email });
  }

  if (route === 'POST /api/auth/login') {
    if (rateLimited(req, 'login', 10)) return sendJson(res, 429, { error: 'too_many_requests' });
    const body = await readJson(req);
    const email = normalizeEmail(body.email);
    const user = EMAIL_RE.test(email) ? store.data.users[email] : undefined;
    if (!user || typeof body.password !== 'string' || !verifyPassword(body.password, user.passwordHash)) {
      return sendJson(res, 401, { error: 'invalid_credentials' });
    }
    return sendJson(res, 200, { token: createSession(user.id), email });
  }

  if (route === 'POST /api/auth/logout') {
    if (token) {
      delete store.data.sessions[sha256(token)];
      store.save();
    }
    return sendJson(res, 200, { ok: true });
  }

  if (route === 'GET /api/devices') {
    const userId = sessionUserId(token);
    if (!userId) return sendJson(res, 401, { error: 'unauthorized' });
    const devices = Object.entries(store.data.devices)
      .filter(([, device]) => device.ownerId === userId)
      .map(([id]) => deviceView(id));
    return sendJson(res, 200, { devices });
  }

  // A câmera faz login uma vez e registra o aparelho na conta.
  if (route === 'POST /api/devices/register') {
    const userId = sessionUserId(token);
    if (!userId) return sendJson(res, 401, { error: 'unauthorized' });
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
    });
    res.end(data);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (url.pathname.startsWith('/api/')) {
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
    const userId = sessionUserId(url.searchParams.get('token'));
    const device = deviceId ? store.data.devices[deviceId] : undefined;
    if (!userId || !device || device.ownerId !== userId) return reject(socket, '403 Forbidden');
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

server.listen(PORT, () => {
  console.log(`Relay ouvindo na porta ${PORT}`);
  console.log(signupOpen() ? 'Cadastro aberto.' : 'Cadastro fechado (use ALLOW_SIGNUP=1 para abrir).');
});
