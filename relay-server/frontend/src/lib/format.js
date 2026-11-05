const LOW_BATTERY = 20;

/** Saúde do aparelho em etiquetas: bateria, temperatura e aviso de superaquecimento. */
export function healthChips(health) {
  if (!health) return [];
  const chips = [];
  if (health.battery !== null) {
    chips.push({
      icon: 'battery',
      text: `${health.battery}%${health.charging ? ' · carregando' : ''}`,
      warn: health.battery < LOW_BATTERY && !health.charging,
    });
  }
  if (health.temperature !== null) {
    chips.push({ icon: 'thermometer', text: `${Math.round(health.temperature)} °C`, warn: health.throttled });
  }
  if (health.throttled) chips.push({ icon: 'alert', text: 'Quente, qualidade reduzida', warn: true });
  return chips;
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
