#!/bin/sh
# Copia o volume de dados (montado em /data) para /backups/relay-data-<data>.tar.gz
# e mantém só as BACKUP_KEEP cópias mais recentes. Roda dentro do serviço "backup".
set -eu

KEEP="${BACKUP_KEEP:-14}"
STAMP="$(date -u +%Y-%m-%d-%H%M%S)"
FILE="/backups/relay-data-$STAMP.tar.gz"

# Grava num .tmp e renomeia: uma cópia interrompida nunca parece completa.
tar czf "$FILE.tmp" -C /data .
mv "$FILE.tmp" "$FILE"

# Apaga as cópias mais antigas (o nome tem a data, então a ordem alfabética é a cronológica).
TOTAL="$(ls -1 /backups/relay-data-*.tar.gz | wc -l)"
if [ "$TOTAL" -gt "$KEEP" ]; then
  ls -1 /backups/relay-data-*.tar.gz | sort | head -n "$((TOTAL - KEEP))" | xargs -r rm -f
fi

echo "$(date -u '+%Y-%m-%d %H:%M:%S') UTC: backup salvo em $(basename "$FILE") ($(du -k "$FILE" | cut -f1) KB)"
