'use strict';

/**
 * Configurações da câmera controláveis pelo site (controle remoto).
 * O servidor só repassa valores conhecidos: o resto é descartado.
 */
const CHOICES = {
  lens: ['back', 'front'],
  quality: ['low', 'medium', 'high'],
  sensitivity: ['low', 'medium', 'high'],
  rotation: [0, 90, 180, 270],
};
// video: gravar o vídeo curto do movimento.
const FLAGS = ['motion', 'torch', 'video'];

// Zoom: número entre 0,1 e 20, com uma casa decimal (abaixo de 1 usa a grande-angular, se houver).
function zoomValue(value) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0.1 || value > 20) return undefined;
  return Math.round(value * 10) / 10;
}

/** Mudanças pedidas pelo site: só as chaves e valores válidos. */
function sanitizeChanges(input) {
  const changes = {};
  if (!input || typeof input !== 'object') return changes;
  for (const [key, allowed] of Object.entries(CHOICES)) {
    if (allowed.includes(input[key])) changes[key] = input[key];
  }
  for (const key of FLAGS) {
    if (typeof input[key] === 'boolean') changes[key] = input[key];
  }
  const zoom = zoomValue(input.zoom);
  if (zoom !== undefined) changes.zoom = zoom;
  return changes;
}

/** Estado informado pela câmera: as mesmas chaves, mais se a lente atual tem lanterna. */
function sanitizeReport(input) {
  const settings = sanitizeChanges(input);
  if (input && typeof input.torchAvailable === 'boolean') settings.torchAvailable = input.torchAvailable;
  // Faixa de zoom do celular (ex.: 0,6 a 8).
  const min = zoomValue(input?.zoomMin);
  const max = zoomValue(input?.zoomMax);
  if (min !== undefined && max !== undefined && min <= max) Object.assign(settings, { zoomMin: min, zoomMax: max });
  return settings;
}

module.exports = { sanitizeChanges, sanitizeReport, CHOICES };
