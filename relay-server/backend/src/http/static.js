'use strict';

const fs = require('node:fs');
const path = require('node:path');

// Arquivos fixos da página. Tudo vem do build do frontend (frontend/dist).
const STATIC_FILES = new Map([
  ['/', { file: 'index.html', type: 'text/html; charset=utf-8' }],
  ['/sw.js', { file: 'sw.js', type: 'text/javascript; charset=utf-8' }],
  ['/favicon.ico', { file: 'favicon.ico', type: 'image/x-icon' }],
  ['/icon-192.png', { file: 'icon-192.png', type: 'image/png' }],
  ['/apple-touch-icon.png', { file: 'apple-touch-icon.png', type: 'image/png' }],
]);

const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': "default-src 'self'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'",
};

function resolve(pathname) {
  const fixed = STATIC_FILES.get(pathname);
  if (fixed) return { ...fixed, immutable: false };
  // Os arquivos do build têm um hash no nome, então podem ficar em cache para sempre.
  const asset = pathname.match(/^\/assets\/([a-zA-Z0-9._-]+)$/);
  if (!asset) return null;
  return {
    file: path.join('assets', asset[1]),
    type: asset[1].endsWith('.css') ? 'text/css; charset=utf-8' : 'text/javascript; charset=utf-8',
    immutable: true,
  };
}

function createStaticHandler(webDir) {
  return function serveStatic(pathname, res) {
    const entry = resolve(pathname);
    if (!entry) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('Não encontrado');
    }
    fs.readFile(path.join(webDir, entry.file), (err, content) => {
      if (err && err.code === 'ENOENT') {
        // Sem a página compilada (no desenvolvimento, quem serve a página é o Vite).
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end(pathname === '/'
          ? 'Página não compilada. Em desenvolvimento, abra http://localhost:5173.'
          : 'Não encontrado');
      }
      if (err) {
        res.writeHead(500);
        return res.end();
      }
      res.writeHead(200, {
        'Content-Type': entry.type,
        'Cache-Control': entry.immutable ? 'public, max-age=31536000, immutable' : 'no-cache',
        ...SECURITY_HEADERS,
      });
      res.end(content);
    });
  };
}

module.exports = { createStaticHandler };
