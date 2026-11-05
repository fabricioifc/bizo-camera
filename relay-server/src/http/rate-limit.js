'use strict';

/** Limite de tentativas por IP e por chave (ex.: "mfa"), numa janela de tempo. */
function createRateLimiter({ trustProxy }) {
  const buckets = new Map(); // chave:ip -> { count, resetAt }

  function clientIp(req) {
    // Atrás do cloudflared, o IP real vem no X-Forwarded-For.
    const forwarded = trustProxy ? req.headers['x-forwarded-for'] : undefined;
    return (forwarded ? forwarded.split(',')[0].trim() : req.socket.remoteAddress) || 'unknown';
  }

  return {
    /** Conta uma tentativa e diz se passou do limite. */
    limited(req, key, max, windowMs = 60_000) {
      const bucketKey = `${key}:${clientIp(req)}`;
      const now = Date.now();
      let bucket = buckets.get(bucketKey);
      if (!bucket || bucket.resetAt < now) {
        bucket = { count: 0, resetAt: now + windowMs };
        buckets.set(bucketKey, bucket);
      }
      bucket.count += 1;
      return bucket.count > max;
    },

    sweep(now = Date.now()) {
      for (const [key, bucket] of buckets) if (bucket.resetAt < now) buckets.delete(key);
    },
  };
}

module.exports = { createRateLimiter };
