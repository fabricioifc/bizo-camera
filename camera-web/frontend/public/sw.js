'use strict';

// Service worker: deixa o site instalável e abrindo sem rede (casca em cache), e mostra os alertas
// (movimento, câmera offline, tomada, bateria, calor) mesmo com o site fechado.

// App instalado: guarda a "casca" do site (página e arquivos do build) para abrir mesmo sem rede.
// A API, as fotos e os vídeos nunca ficam em cache.
const CACHE = 'monitor-shell-v1';
const SHELL = ['/', '/manifest.webmanifest', '/icon-192.png', '/icon-512.png', '/favicon.ico'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws/')) return;

  // Página: sempre da rede (versão nova na hora); sem rede, a última guardada.
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put('/', copy));
          return response;
        })
        .catch(() => caches.match('/')),
    );
    return;
  }

  // Arquivos do build (nome com hash, nunca mudam): do cache, ou da rede e guarda.
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      caches.match(request).then((cached) => cached || fetch(request).then((response) => {
        const copy = response.clone();
        if (response.ok) caches.open(CACHE).then((cache) => cache.put(request, copy));
        return response;
      })),
    );
  }
});

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    // Mensagem sem JSON: mostra um alerta genérico.
  }
  const time = data.at
    ? new Date(data.at).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
    : '';
  // Alertas da câmera trazem o texto pronto; o de movimento mostra a câmera e o horário.
  const body = data.body
    ? [data.body, time].filter(Boolean).join(' · ')
    : [data.device, time].filter(Boolean).join(' às ');
  const kind = data.kind || 'motion';
  // Offline e "de volta" dividem a mesma notificação: a mais nova substitui a anterior.
  const group = kind === 'online' ? 'offline' : kind;
  event.waitUntil(
    self.registration.showNotification(data.title || 'Movimento detectado', {
      body,
      icon: '/icon-192.png',
      // Uma notificação por câmera e tipo de alerta: um novo alerta substitui o anterior.
      tag: data.deviceId ? `${group}-${data.deviceId}` : group,
      renotify: true,
      data: { deviceId: data.deviceId },
    }),
  );
});

// Toque na notificação: abre (ou foca) o site já na câmera.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const deviceId = event.notification.data && event.notification.data.deviceId;
  const url = deviceId ? `/#camera=${encodeURIComponent(deviceId)}` : '/';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((client) => new URL(client.url).origin === self.location.origin);
      if (open) {
        open.navigate(url);
        return open.focus();
      }
      return self.clients.openWindow(url);
    }),
  );
});
