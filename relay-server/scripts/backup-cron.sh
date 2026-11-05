#!/bin/sh
# Ponto de entrada do serviço "backup": faz uma cópia ao subir e agenda as próximas no cron.
# BACKUP_SCHEDULE usa o horário UTC (o padrão, 06:00 UTC, é 03:00 em Brasília).
set -eu

SCHEDULE="${BACKUP_SCHEDULE:-0 6 * * *}"

# A saída do cron vai para o log do container (docker compose logs backup).
echo "$SCHEDULE /bin/sh /scripts/backup.sh >> /proc/1/fd/1 2>&1" > /etc/crontabs/root
echo "Backup agendado: \"$SCHEDULE\" (UTC), guardando ${BACKUP_KEEP:-14} cópias. Fazendo uma cópia agora."
/bin/sh /scripts/backup.sh

exec crond -f -l 8
