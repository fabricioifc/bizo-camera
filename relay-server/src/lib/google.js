'use strict';

const crypto = require('node:crypto');

// Login com Google pelo fluxo de código de autorização (OpenID Connect) com PKCE.
// Só funciona quando GOOGLE_CLIENT_ID e GOOGLE_CLIENT_SECRET estiverem definidos.

const AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const CERTS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
const ISSUERS = ['https://accounts.google.com', 'accounts.google.com'];
const CERTS_TTL_MS = 60 * 60 * 1000;

let certsCache = { keys: [], fetchedAt: 0 };

function randomUrl(bytes) {
  return crypto.randomBytes(bytes).toString('base64url');
}

// Cria os valores de uma tentativa de login: state (contra CSRF), nonce e PKCE.
function newAttempt() {
  const verifier = randomUrl(32);
  return {
    state: randomUrl(24),
    nonce: randomUrl(24),
    verifier,
    challenge: crypto.createHash('sha256').update(verifier).digest('base64url'),
  };
}

function authorizationUrl({ clientId, redirectUri, attempt }) {
  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'openid email',
    state: attempt.state,
    nonce: attempt.nonce,
    code_challenge: attempt.challenge,
    code_challenge_method: 'S256',
    prompt: 'select_account',
  });
  return `${AUTH_URL}?${params}`;
}

async function exchangeCode({ clientId, clientSecret, redirectUri, code, verifier }) {
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      code,
      client_id: clientId,
      client_secret: clientSecret,
      redirect_uri: redirectUri,
      grant_type: 'authorization_code',
      code_verifier: verifier,
    }),
  });
  if (!res.ok) throw new Error('token_exchange_failed');
  const data = await res.json();
  if (!data.id_token) throw new Error('missing_id_token');
  return data.id_token;
}

async function signingKeys() {
  if (Date.now() - certsCache.fetchedAt > CERTS_TTL_MS) {
    const res = await fetch(CERTS_URL);
    if (!res.ok) throw new Error('certs_unavailable');
    const data = await res.json();
    certsCache = { keys: data.keys || [], fetchedAt: Date.now() };
  }
  return certsCache.keys;
}

// Valida assinatura, emissor, audiência, validade e (se informado) o nonce do ID token.
// O login nativo do app não usa nonce; o da web usa.
async function verifyIdToken(idToken, { clientId, nonce }) {
  const [headerPart, payloadPart, signaturePart] = idToken.split('.');
  if (!signaturePart) throw new Error('malformed_token');

  const header = JSON.parse(Buffer.from(headerPart, 'base64url').toString('utf8'));
  const payload = JSON.parse(Buffer.from(payloadPart, 'base64url').toString('utf8'));
  if (header.alg !== 'RS256') throw new Error('unexpected_alg');

  const jwk = (await signingKeys()).find((key) => key.kid === header.kid);
  if (!jwk) throw new Error('unknown_key');
  const publicKey = crypto.createPublicKey({ key: jwk, format: 'jwk' });
  const valid = crypto.verify(
    'RSA-SHA256',
    Buffer.from(`${headerPart}.${payloadPart}`),
    publicKey,
    Buffer.from(signaturePart, 'base64url'),
  );
  if (!valid) throw new Error('bad_signature');

  if (!ISSUERS.includes(payload.iss)) throw new Error('bad_issuer');
  if (payload.aud !== clientId) throw new Error('bad_audience');
  if (!payload.exp || payload.exp * 1000 < Date.now()) throw new Error('token_expired');
  if (nonce !== undefined && payload.nonce !== nonce) throw new Error('bad_nonce');
  if (payload.email_verified !== true) throw new Error('email_not_verified');
  if (!payload.sub || !payload.email) throw new Error('missing_claims');
  return { sub: payload.sub, email: payload.email };
}

module.exports = { newAttempt, authorizationUrl, exchangeCode, verifyIdToken };
