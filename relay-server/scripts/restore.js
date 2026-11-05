'use strict';

// Restaura um backup no volume de dados do servidor. APAGA o conteúdo atual do volume.
//
// 1. Pare o servidor:      docker compose stop relay
// 2. Restaure:             npm run restore -- backups/relay-data-<data>.tar.gz --yes
// 3. Suba de novo:         docker compose up -d
//
// Sem --yes, só mostra o que seria feito. Volume diferente: RELAY_VOLUME=nome_do_volume.

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const VOLUME = process.env.RELAY_VOLUME || 'relay-server_relay-data';
const args = process.argv.slice(2);
const file = args.find((arg) => !arg.startsWith('--'));
const confirmed = args.includes('--yes');

if (!file || !fs.existsSync(file)) {
  console.error('Informe o arquivo de backup: npm run restore -- backups/relay-data-<data>.tar.gz --yes');
  process.exit(1);
}

const backup = path.resolve(file);
if (!confirmed) {
  console.log(`Isto vai APAGAR o volume "${VOLUME}" e colocar no lugar o conteúdo de ${path.basename(backup)}.`);
  console.log('Pare o servidor antes (docker compose stop relay) e rode de novo com --yes para confirmar.');
  process.exit(0);
}

execFileSync(
  'docker',
  [
    'run', '--rm',
    '-v', `${VOLUME}:/data`,
    '-v', `${path.dirname(backup)}:/backup:ro`,
    'alpine:3.20', 'sh', '-c',
    // Limpa o volume, extrai o backup e devolve os arquivos ao usuário "node" (uid 1000) do container.
    `find /data -mindepth 1 -delete && tar xzf "/backup/${path.basename(backup)}" -C /data && chown -R 1000:1000 /data`,
  ],
  { stdio: 'inherit' },
);

console.log(`Volume "${VOLUME}" restaurado a partir de ${path.basename(backup)}. Suba o servidor: docker compose up -d`);
