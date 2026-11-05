'use strict';

const fs = require('node:fs/promises');
const { createReadStream } = require('node:fs');
const { readJson, sendJson } = require('../http/respond');
const { requireFullSession } = require('./guards');

const DEVICE = /^\/api\/devices\/([a-f0-9]{16})$/;
const DEVICE_EVENTS = /^\/api\/devices\/([a-f0-9]{16})\/events$/;
const DEVICE_PHOTO = /^\/api\/devices\/([a-f0-9]{16})\/photo$/;
const EVENT = /^\/api\/events\/([a-f0-9]{16})$/;
const EVENT_SNAPSHOT = /^\/api\/events\/([a-f0-9]{16})\/snapshot$/;
const EVENT_CLIP = /^\/api\/events\/([a-f0-9]{16})\/clip$/;

/** Câmeras da conta: lista, registro, nome, remoção, movimentos e fotos. */
function deviceRoutes(router, app) {
  const { devices } = app;
  const notFound = (res) => sendJson(res, 404, { error: 'not_found' });

  router.get('/api/devices', (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    return sendJson(ctx.res, 200, { devices: devices.listFor(userId) });
  });

  // A câmera faz login uma vez e registra o aparelho na conta.
  router.post('/api/devices/register', async (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    const body = await readJson(ctx.req);
    return sendJson(ctx.res, 201, devices.register(userId, body.name));
  });

  router.patch(DEVICE, async (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    const device = devices.owned(userId, ctx.params[0]);
    if (!device) return notFound(ctx.res);
    const body = await readJson(ctx.req);
    const name = devices.rename(device, body && body.name);
    if (!name) return sendJson(ctx.res, 400, { error: 'invalid_name' });
    return sendJson(ctx.res, 200, { ok: true, name });
  });

  router.delete(DEVICE, (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    if (!devices.owned(userId, ctx.params[0])) return notFound(ctx.res);
    devices.remove(ctx.params[0]);
    return sendJson(ctx.res, 200, { ok: true });
  });

  // "Foto agora": a câmera tira uma foto em qualidade alta, guardada junto com os movimentos.
  router.post(DEVICE_PHOTO, async (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    if (!devices.owned(userId, ctx.params[0])) return notFound(ctx.res);
    const result = await devices.takePhoto(ctx.params[0]);
    if (result.event) return sendJson(ctx.res, 201, { event: result.event });
    const status = { camera_offline: 409, busy: 409, timeout: 504 }[result.error] || 502;
    return sendJson(ctx.res, status, { error: result.error });
  });

  // Últimos movimentos de uma câmera da conta.
  router.get(DEVICE_EVENTS, (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    if (!devices.owned(userId, ctx.params[0])) return notFound(ctx.res);
    return sendJson(ctx.res, 200, { events: devices.recentEvents(ctx.params[0]) });
  });

  // Nome do arquivo baixado: câmera e data/hora do evento.
  function downloadName(userId, eventId, extension) {
    const found = devices.eventFor(userId, eventId);
    if (!found) return `evento.${extension}`;
    const name = found.device.name.normalize('NFD').replace(/[^\w-]+/g, '-').replace(/^-|-$/g, '') || 'camera';
    const at = new Date(found.event.at).toISOString().slice(0, 19).replace(/[T:]/g, '-');
    return `${name}-${at}.${extension}`;
  }

  // Foto de um evento: só para o dono da câmera. Com ?download=1, vem como arquivo para baixar.
  router.get(EVENT_SNAPSHOT, async (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    const file = devices.snapshotFor(userId, ctx.params[0]);
    let data;
    try {
      data = file && (await fs.readFile(file));
    } catch {
      data = null;
    }
    if (!data) return notFound(ctx.res);
    const headers = {
      'Content-Type': 'image/jpeg',
      'Cache-Control': 'private, max-age=86400',
      'X-Content-Type-Options': 'nosniff',
    };
    if (ctx.url.searchParams.has('download')) {
      headers['Content-Disposition'] = `attachment; filename="${downloadName(userId, ctx.params[0], 'jpg')}"`;
    }
    ctx.res.writeHead(200, headers);
    ctx.res.end(data);
  });

  // Vídeo de um movimento (MP4). Aceita Range, que o navegador usa para avançar e voltar no vídeo.
  router.get(EVENT_CLIP, async (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    const file = devices.clipFor(userId, ctx.params[0]);
    let size;
    try {
      size = file && (await fs.stat(file)).size;
    } catch {
      size = null;
    }
    if (!size) return notFound(ctx.res);

    const headers = {
      'Content-Type': 'video/mp4',
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'private, max-age=86400',
      'X-Content-Type-Options': 'nosniff',
    };
    if (ctx.url.searchParams.has('download')) {
      headers['Content-Disposition'] = `attachment; filename="${downloadName(userId, ctx.params[0], 'mp4')}"`;
    }
    let start = 0;
    let end = size - 1;
    let status = 200;
    const range = /^bytes=(\d*)-(\d*)$/.exec(ctx.req.headers.range || '');
    if (range && (range[1] || range[2])) {
      if (range[1]) {
        start = Number(range[1]);
        if (range[2]) end = Math.min(Number(range[2]), size - 1);
      } else {
        start = Math.max(0, size - Number(range[2]));
      }
      if (start > end || start >= size) {
        ctx.res.writeHead(416, { 'Content-Range': `bytes */${size}` });
        return ctx.res.end();
      }
      status = 206;
      headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
    }
    headers['Content-Length'] = end - start + 1;
    ctx.res.writeHead(status, headers);
    createReadStream(file, { start, end }).pipe(ctx.res);
  });

  // Remove um evento (foto e vídeo).
  router.delete(EVENT, (ctx) => {
    const userId = requireFullSession(ctx);
    if (!userId) return;
    if (!devices.removeEvent(userId, ctx.params[0])) return notFound(ctx.res);
    return sendJson(ctx.res, 200, { ok: true });
  });
}

module.exports = { deviceRoutes };
