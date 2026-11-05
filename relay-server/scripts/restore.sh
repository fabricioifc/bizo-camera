#!/bin/sh
# Restaura um backup no volume de dados. APAGA o conteúdo atual do volume.
# Roda no serviço "restore" (perfil restore do docker-compose.yml), com o servidor parado:
#   docker compose stop relay
#   docker compose run --rm restore /backups/relay-data-<data>.tar.gz
#   docker compose up -d
set -eu

FILE="${1:-}"
if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
  echo "Informe o arquivo: docker compose run --rm restore /backups/relay-data-<data>.tar.gz"
  echo "Cópias disponíveis:"
  ls -1 /backups 2>/dev/null || true
  exit 1
fi

find /data -mindepth 1 -delete
tar xzf "$FILE" -C /data
# Devolve os arquivos ao usuário "node" (uid 1000) do container do servidor.
chown -R 1000:1000 /data
echo "Volume restaurado a partir de $(basename "$FILE"). Suba o servidor: docker compose up -d"
