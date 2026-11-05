const LOW_BATTERY = 20;

export function healthText(health) {
  if (!health) return '';
  const parts = [];
  if (health.battery !== null) parts.push(`Bateria ${health.battery}%${health.charging ? ' (carregando)' : ''}`);
  if (health.temperature !== null) parts.push(`${Math.round(health.temperature)} °C`);
  if (health.throttled) parts.push('quente, qualidade reduzida');
  return parts.join(' · ');
}

export function healthWarn(health) {
  return Boolean(health && ((health.battery !== null && health.battery < LOW_BATTERY && !health.charging) || health.throttled));
}

export function formatWhen(at) {
  const date = new Date(at);
  const time = date.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  if (date.toDateString() === new Date().toDateString()) return `hoje às ${time}`;
  return `${date.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })} às ${time}`;
}

export function snapshotUrl(eventId) {
  return `/api/events/${encodeURIComponent(eventId)}/snapshot`;
}
