export const pushSupported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;

let registration = null;

/**
 * Registra o service worker uma vez (alertas e app instalável); resolve com null se não houver
 * suporte ou falhar.
 */
export function serviceWorker() {
  if (!('serviceWorker' in navigator)) return Promise.resolve(null);
  registration ||= navigator.serviceWorker.register('/sw.js').catch(() => null);
  return registration;
}

export function base64UrlToBytes(text) {
  const base64 = (text + '='.repeat((4 - (text.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  return Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
}
