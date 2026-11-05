# Implantação e operação

Como publicar o servidor, configurar o acesso e cuidar dos dados. Para rodar em desenvolvimento, veja o [README do servidor](../camera-web/README.md).

## Variáveis de ambiente

| Variável | Padrão | Uso |
|---|---|---|
| `PORT` | `8787` | Porta HTTP. |
| `PUBLIC_URL` | `http://localhost:8787` | Endereço público do site. Usado no retorno do login com Google. Com `https://`, o cookie de sessão vira `Secure`. |
| `DATA_FILE` | `camera-web/data/db.json` | Arquivo onde ficam usuários, câmeras e sessões. No Docker, é `/data/db.json`. |
| `ALLOW_SIGNUP` | (vazio) | Defina `1` para permitir novos cadastros além do primeiro usuário. |
| `TRUST_PROXY` | (vazio) | Defina `1` atrás de um proxy ou túnel (cloudflared, Caddy, Nginx), para limitar tentativas por IP real. |
| `REQUIRE_2FA` | `1` | Exige verificação em duas etapas no login e antes do acesso às câmeras. No Compose de desenvolvimento fica desativado. |
| `GOOGLE_CLIENT_ID` | (vazio) | Cliente OAuth do Google. Sem ele, o botão de login com Google fica desativado. |
| `GOOGLE_CLIENT_SECRET` | (vazio) | Segredo do cliente OAuth. Nunca versione este valor. |
| `CLIP_STORAGE_MB` | `500` | Espaço total para os vídeos de movimento; acima disso, os mais antigos são apagados. |
| `OFFLINE_ALERT_MS` | `300000` | Tempo sem conexão até o alerta "Câmera offline" (em milissegundos). |
| `APP_PACKAGE` | `br.dev.bizo.cameramonitor` | Pacote do app aceito na atualização pelo próprio app. |
| `WEB_DIR` | `camera-web/frontend/dist` | Pasta da página compilada servida pelo backend. |
| `BACKUP_SCHEDULE` | `0 6 * * *` | Horário diário do backup em UTC, no formato cron. |
| `BACKUP_KEEP` | `14` | Quantidade de backups a manter. |
| `BACKUP_DIR` | `./backups` | Pasta do servidor onde ficam os backups. No Dokploy, use uma pasta fixa (ex.: `/home/fabricio/backups/relay-server`), porque o código é clonado de novo a cada deploy. |

No Docker, configure essas variáveis em `camera-web/.env`, criado a partir de `.env.example`. Esse arquivo é carregado pelo Docker Compose e está no `.gitignore`. Para publicar sua própria instância, defina `PUBLIC_URL` com o endereço HTTPS público e configure o túnel ou proxy para encaminhar o tráfego ao relay.

## Criar a primeira conta

A primeira conta pode ser criada sem configuração extra. Depois que existir um usuário, o cadastro fica fechado. Para abrir de novo, troque `ALLOW_SIGNUP` para `"1"` em `docker-compose.yml`, rode `docker compose up -d`, cadastre a conta e volte para `"0"`.

## Expor pelo cloudflared

O túnel roda no host e aponta para `http://localhost:8787`. Com o container ativo, o `config.yml` do túnel continua igual ao passo a passo:

```yaml
ingress:
  - hostname: camera.bizo.dev.br
    service: http://localhost:8787
  - service: http_status:404
```

## Deploy com Dokploy

O [Dokploy](https://dokploy.com) publica o `docker-compose.yml` direto do GitHub, a cada push na `main`. Para usar:

1. Crie um serviço do tipo **Compose** com Provider **Git**, o endereço do repositório, a branch `main` e o Compose Path `./camera-web/docker-compose.yml`.
2. Na aba **Environment**, informe as variáveis do `.env` e defina `BACKUP_DIR` com uma pasta fixa do servidor. O Dokploy clona o código de novo a cada deploy, então a pasta `./backups` não sobreviveria.
3. Faça o primeiro deploy pelo botão **Deploy**.
4. Para publicar a cada push, ligue o **Autodeploy** e cadastre a **Webhook URL** da aba **Deployments** nos webhooks do GitHub, com o evento de push.

Não é preciso cadastrar domínio no Dokploy quando o túnel aponta direto para `localhost:8787`. O volume tem nome fixo (`relay-server_relay-data`), então os dados são os mesmos rodando pelo Dokploy ou por `docker compose`. Só não rode os dois juntos, porque eles disputam a porta 8787.

## Backup

O volume tem tudo o que não dá para recriar: contas, sessões, câmeras, fotos de movimento e as chaves dos alertas (`vapid.json`). O serviço `backup` do `docker-compose.yml` cuida disso sozinho: sobe junto com o servidor (`docker compose up -d`), faz uma cópia ao iniciar e outra todo dia às 03:00 de Brasília.

- As cópias ficam em `camera-web/backups/relay-data-<data>.tar.gz` (fora do git; a data no nome é em UTC), ou na pasta definida em `BACKUP_DIR`.
- Guarda as 14 mais recentes. Horário e quantidade ficam em `BACKUP_SCHEDULE` (formato do cron, em UTC) e `BACKUP_KEEP`, no `docker-compose.yml`.
- O volume é montado só para leitura no serviço de backup, então ele nunca altera os dados.
- Leve as cópias para fora do computador de vez em quando (pendrive ou nuvem privada).

Comandos:

```bash
docker compose logs backup                      # ver as cópias feitas
docker compose exec backup sh /scripts/backup.sh  # fazer uma cópia agora
```

Para restaurar (apaga os dados atuais do volume):

```bash
docker compose stop relay
docker compose run --rm restore /backups/relay-data-<data>.tar.gz
docker compose up -d
```

Sem o nome do arquivo, `docker compose run --rm restore` só lista as cópias disponíveis.

## Saúde do servidor

`GET /healthz` responde `{ "ok": true }` enquanto o servidor atende. O Dockerfile usa essa rota no `HEALTHCHECK`, a cada 30 segundos, e `docker ps` mostra `healthy` ou `unhealthy`. O Docker sozinho só reinicia o container quando o processo cai (`restart: unless-stopped`); um container que ficar `unhealthy` sem cair precisa de `docker compose restart relay`.

## Login com Google

1. No [Google Cloud Console](https://console.cloud.google.com/apis/credentials), crie um cliente OAuth do tipo **Aplicativo da Web**.
2. Em **URIs de redirecionamento autorizados**, adicione:
   `https://SEU_DOMINIO/api/auth/google/callback` (substitua pelo domínio configurado em `PUBLIC_URL`).
3. Copie o **Client ID** e o **Client secret** para o `.env` e reinicie o relay com `docker compose up -d`.

A conta Google só é vinculada a uma conta existente com o mesmo e-mail quando o Google confirma o e-mail (`email_verified`). Se a conta já tiver verificação em duas etapas, ela continua sendo pedida depois do Google.

## Verificação em duas etapas (2FA)

Na web, o login é só pelo Google e o 2FA é obrigatório. Se a conta ainda não tiver 2FA, a sessão fica limitada à tela de **Segurança** até você ativá-lo. Nesse caso, as câmeras só aparecem depois.

- Em **Segurança**, na tela da lista de câmeras, ative o 2FA. O servidor mostra um QR code para o app autenticador (Google Authenticator, Authy, 1Password…).
- Ao confirmar o código, aparecem 8 **códigos de recuperação**. Eles valem uma vez cada e não são mostrados de novo.
- Com o 2FA ativo, o login pede o código depois da senha. Depois de 5 códigos errados, é preciso entrar com a senha de novo.
- Para desativar, informe um código (do app ou de recuperação).

## Proteção contra tentativas

- Depois de 5 códigos 2FA errados, a conta fica bloqueada por 15 minutos.
- Login, cadastro e códigos 2FA têm limite de tentativas por IP, por minuto.
- Cada código TOTP vale uma vez e códigos de recuperação são apagados depois de usados.
