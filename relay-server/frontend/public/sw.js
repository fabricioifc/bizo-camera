'use strict';

// Service worker: recebe os alertas (movimento, câmera offline, tomada, bateria, calor) e mostra a
// notificação, mesmo com o site fechado.

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
