'use strict';

// Copia o volume de dados do servidor (contas, câmeras, fotos de movimento e chaves dos alertas)
// para backups/relay-data-<data>.tar.gz. Pode rodar com o servidor ligado: o banco é gravado
// de forma atômica e o volume é montado só para leitura.
//
// Uso: npm run backup            (mantém as 14 cópias mais recentes)
//      npm run backup -- --keep 30
// Volume diferente: RELAY_VOLUME=nome_do_volume npm run backup

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const VOLUME = process.env.RELAY_VOLUME || 'relay-server_relay-data';
const BACKUP_DIR = path.resolve(__dirname, '..', 'backups');
const keepIndex = process.argv.indexOf('--keep');
const KEEP = keepIndex !== -1 ? Number(process.argv[keepIndex + 1]) : 14;

if (!Number.isInteger(KEEP) || KEEP < 1) {
  console.error('Use --keep com um número inteiro maior que zero.');
  process.exit(1);
}

fs.mkdirSync(BACKUP_DIR, { recursive: true });
const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
const name = `relay-data-${stamp}.tar.gz`;

execFileSync(
  'docker',
  ['run', '--rm', '-v', `${VOLUME}:/data:ro`, '-v', `${BACKUP_DIR}:/backup`, 'alpine:3.20', 'tar', 'czf', `/backup/${name}`, '-C', '/data', '.'],
  { stdio: 'inherit' },
);

// Mantém só as cópias mais recentes (os nomes têm a data, então a ordem alfabética é a cronológica).
const backups = fs.readdirSync(BACKUP_DIR).filter((file) => /^relay-data-.*\.tar\.gz$/.test(file)).sort();
for (const old of backups.slice(0, Math.max(0, backups.length - KEEP))) {
  fs.rmSync(path.join(BACKUP_DIR, old));
}

const size = (fs.statSync(path.join(BACKUP_DIR, name)).size / 1024).toFixed(0);
console.log(`Backup salvo em backups/${name} (${size} KB). Cópias guardadas: ${Math.min(backups.length, KEEP)}.`);
