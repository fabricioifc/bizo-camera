'use strict';

// Utilitários dos testes: sobe o servidor de verdade numa porta livre, com um banco temporário.

const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const WebSocket = require('ws');

const SERVER = path.join(__dirname, '..', 'src', 'server.js');

const sha256 = (value) => crypto.createHash('sha256').update(value).digest('hex');
const randomToken = () => crypto.randomBytes(32).toString('hex');
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function freePort() {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.on('error', reject);
    probe.listen(0, () => {
      const { port } = probe.address();
      probe.close(() => resolve(port));
    });
  });
}

/**
 * Banco inicial com uma conta (u1) já logada pelo Google e uma câmera dela.
 * Devolve o banco e os segredos para usar nas requisições.
 */
function seedAccount({ pending = false, twoFactor = false } = {}) {
  const session = randomToken();
  const deviceSecret = randomToken();
  const deviceId = crypto.randomBytes(8).toString('hex');
  const user = { id: 'u1', googleId: 'g1', createdAt: Date.now() };
  if (twoFactor) user.totp = { secret: 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', enabled: true, lastStep: -1, recovery: [] };
  const db = {
    users: { 'dona@example.com': user },
    sessions: { [sha256(session)]: { userId: 'u1', expiresAt: Date.now() + 3_600_000, pending } },
    devices: { [deviceId]: { ownerId: 'u1', name: 'Mi A3', secretHash: sha256(deviceSecret), createdAt: Date.now() } },
  };
  return { db, session, deviceId, deviceSecret };
}

/** Sobe o servidor. [seed] é o conteúdo inicial do db.json; [env] sobrescreve variáveis de ambiente. */
async function startServer({ seed, env = {} } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'relay-test-'));
  const dataFile = path.join(dir, 'db.json');
  if (seed) fs.writeFileSync(dataFile, JSON.stringify(seed));
  const port = await freePort();
  const base = `http://localhost:${port}`;
  const child = spawn(process.execPath, [SERVER], {
    env: {
      ...process.env,
      PORT: String(port),
      DATA_FILE: dataFile,
      PUBLIC_URL: base,
      GOOGLE_CLIENT_ID: '',
      GOOGLE_CLIENT_SECRET: '',
      ALLOW_SIGNUP: '',
      ...env,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (chunk) => { output += chunk; });
  child.stderr.on('data', (chunk) => { output += chunk; });

  for (let i = 0; ; i++) {
    try {
      await fetch(`${base}/healthz`);
      break;
    } catch (err) {
      if (i > 100) throw new Error(`O servidor não subiu:\n${output}`);
      await wait(50);
    }
  }

  return {
    base,
    dataFile,
    readDb: () => JSON.parse(fs.readFileSync(dataFile, 'utf8')),

    async call(method, route, { token, cookie, body, raw = false } = {}) {
      const headers = {};
      if (token) headers.Authorization = `Bearer ${token}`;
      if (cookie) headers.Cookie = cookie;
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      const res = await fetch(base + route, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
        redirect: 'manual',
      });
      const type = res.headers.get('content-type') || '';
      const data = raw || !type.includes('json') ? Buffer.from(await res.arrayBuffer()) : await res.json();
      return { status: res.status, data, headers: res.headers };
    },

    // Abre um WebSocket e guarda as mensagens de texto recebidas (JSON).
    open(route, headers = {}) {
      return new Promise((resolve, reject) => {
        const ws = new WebSocket(`ws://localhost:${port}${route}`, { headers });
        ws.messages = [];
        ws.on('message', (data, isBinary) => {
          if (!isBinary) ws.messages.push(JSON.parse(data.toString()));
        });
        ws.once('open', () => resolve(ws));
        ws.once('error', reject);
        ws.once('unexpected-response', (req, res) => reject(new Error(`HTTP ${res.statusCode}`)));
      });
    },

    async stop() {
      const exited = new Promise((resolve) => child.once('exit', resolve));
      child.kill();
      await exited;
      fs.rmSync(dir, { recursive: true, force: true });
    },
  };
}

module.exports = { startServer, seedAccount, sha256, randomToken, wait };
