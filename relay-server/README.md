# Servidor de retransmissão (relay)

Liga a câmera (app Android) aos visualizadores (navegador). Também entrega o site de visualização em `/`.

## Rodar com Docker

Requer Docker Desktop (ou Docker Engine + Compose).

Configure as variáveis de ambiente antes de iniciar pela primeira vez:

```bash
cd relay-server
cp .env.example .env
```

Edite `.env` e ajuste `PUBLIC_URL` para o domínio público do seu servidor (por exemplo, `https://camera.seudominio.com`). Se usar login com Google, informe também `GOOGLE_CLIENT_ID` e `GOOGLE_CLIENT_SECRET` e cadastre `https://SEU_DOMINIO/api/auth/google/callback` como URI de redirecionamento no Google Cloud Console. O `.env` é local e não deve ser compartilhado ou enviado ao Git.

```bash
docker compose up -d --build
```

O servidor fica em `http://localhost:8787`, publicado só em `127.0.0.1`. Os dados (usuários, câmeras e sessões) ficam no volume `relay-data`, então sobrevivem a rebuilds e reinícios.

### Desenvolvimento local com login Google

O `PUBLIC_URL` do `.env` é usado para construir o callback OAuth. Para desenvolver em `http://localhost:8787` sem alterar o endereço de produção, use o arquivo Compose de desenvolvimento:

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

Esse arquivo Compose é independente e usa `http://localhost:8787` como endereço de callback e um volume de dados separado (`relay-data-dev`). No Google Cloud Console, adicione `http://localhost:8787/api/auth/google/callback` às URIs de redirecionamento autorizadas do cliente OAuth usado localmente. O navegador também deve abrir o endereço exatamente como `http://localhost:8787` (não `127.0.0.1`). Para voltar à configuração normal, execute `docker compose up -d --build relay`.

Comandos úteis:

```bash
docker compose logs -f relay     # acompanhar o log
docker compose restart relay     # reiniciar
docker compose down              # parar (mantém os dados)
```

Para apagar os dados junto com o volume, use `docker compose down -v`. Isso remove todas as contas.

### Saúde do servidor

`GET /healthz` responde `{ "ok": true }` enquanto o servidor atende. O Dockerfile usa essa rota no `HEALTHCHECK`, a cada 30 segundos, e `docker ps` mostra `healthy` ou `unhealthy`. O Docker sozinho só reinicia o container quando o processo cai (`restart: unless-stopped`); um container que ficar `unhealthy` sem cair precisa de `docker compose restart relay`.

### Backup

O volume tem tudo o que não dá para recriar: contas, sessões, câmeras, fotos de movimento e as chaves dos alertas (`vapid.json`). O serviço `backup` do `docker-compose.yml` cuida disso sozinho: sobe junto com o servidor (`docker compose up -d`), faz uma cópia ao iniciar e outra todo dia às 03:00 de Brasília.

- As cópias ficam em `relay-server/backups/relay-data-<data>.tar.gz` (fora do git; a data no nome é em UTC).
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

## Testes

Roda os testes de `tests/` com o executor do próprio Node (`node --test`). Cada arquivo sobe o servidor de verdade numa porta livre, com um banco temporário: login e sessões, 2FA, login com Google (com chave falsa), saúde, movimento e gerenciamento das câmeras, permissões das fotos, alertas e arquivos da página.

Num container, sem precisar de Node instalado (PowerShell ou Git Bash, na pasta `relay-server`):

```bash
docker run --rm -v "${PWD}:/app" -v /app/node_modules -w /app node:20-alpine sh -c "npm ci && npm test"
```

Com Node instalado, basta `npm test`.

### Expor pelo cloudflared

O túnel roda no host e aponta para `http://localhost:8787`. Com o container ativo, o `config.yml` do túnel continua igual ao passo a passo:

```yaml
ingress:
  - hostname: camera.bizo.dev.br
    service: http://localhost:8787
  - service: http_status:404
```

### Criar a primeira conta

A primeira conta pode ser criada sem configuração extra. Depois que existir um usuário, o cadastro fica fechado. Para abrir de novo, troque `ALLOW_SIGNUP` para `"1"` em `docker-compose.yml`, rode `docker compose up -d`, cadastre a conta e volte para `"0"`.

## Rodar sem Docker

```bash
cd relay-server
npm install
npm start
```

Abra http://localhost:8787 no navegador.

## Variáveis de ambiente

| Variável | Padrão | Uso |
|---|---|---|
| `PORT` | `8787` | Porta HTTP. |
| `PUBLIC_URL` | `http://localhost:8787` | Endereço público do site. Usado no retorno do login com Google. Com `https://`, o cookie de sessão vira `Secure`. |
| `DATA_FILE` | `data/db.json` | Arquivo onde ficam usuários, câmeras e sessões. No Docker, é `/data/db.json`. |
| `ALLOW_SIGNUP` | (vazio) | Defina `1` para permitir novos cadastros além do primeiro usuário. |
| `TRUST_PROXY` | (vazio) | Defina `1` atrás de um proxy ou túnel (cloudflared, Caddy, Nginx), para limitar tentativas por IP real. |
| `GOOGLE_CLIENT_ID` | (vazio) | Cliente OAuth do Google. Sem ele, o botão de login com Google fica desativado. |
| `GOOGLE_CLIENT_SECRET` | (vazio) | Segredo do cliente OAuth. Nunca versione este valor. |
| `BACKUP_SCHEDULE` | `0 6 * * *` | Horário diário do backup em UTC, no formato cron. |
| `BACKUP_KEEP` | `14` | Quantidade de backups a manter. |

No Docker, configure essas variáveis em `relay-server/.env`, criado a partir de `.env.example`. Esse arquivo é carregado pelo Docker Compose e está no `.gitignore`. Para publicar sua própria instância, defina `PUBLIC_URL` com o endereço HTTPS público e configure o túnel ou proxy para encaminhar o tráfego ao relay.

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

## Alertas de movimento

- O app da câmera detecta movimento e envia um evento com uma foto. O servidor aceita no máximo um alerta por câmera a cada 30 segundos e guarda os 50 eventos mais recentes de cada câmera.
- As fotos ficam em `snapshots/`, ao lado do banco (no Docker, dentro do volume `relay-data`). Só o dono da câmera consegue abri-las.
- Para receber notificações, toque em **Ativar alertas** na lista de câmeras, em cada navegador ou celular onde quer o aviso. O navegador pede permissão.
- As notificações usam Web Push. As chaves VAPID são geradas na primeira execução e salvas em `vapid.json`, também ao lado do banco. Se esse arquivo for apagado, os alertas precisam ser ativados de novo em cada aparelho.
- No iPhone, as notificações só funcionam com o site adicionado à tela de início (iOS 16.4 ou mais novo).

## Endpoints

- `GET /api/config`: diz se o cadastro está aberto (`signupOpen`) e se o Google está ativo (`googleEnabled`).
- `POST /api/auth/mfa`: segunda etapa do login pelo Google, com `mfaToken` e `code` (app ou recuperação).
- `POST /api/auth/logout`: encerra a sessão.
- `GET /api/auth/me`: dados da conta logada (`email`, `google`, `twoFactor`, `pending`).
- `GET /api/auth/google/start` e `GET /api/auth/google/callback`: fluxo do login com Google pela web.
- `POST /api/auth/google/app`: login do app Android. Recebe o `idToken` do Google e responde `{ token, email, pending }`, ou `{ mfaRequired, mfaToken }` se a conta tiver 2FA. Sem 2FA ativo, `pending: true` e o app precisa configurar o 2FA com o token antes de usar as câmeras.
- `POST /api/account/2fa/setup`: gera o segredo do 2FA e o QR code.
- `POST /api/account/2fa/enable`: confirma o código e devolve os códigos de recuperação.
- `POST /api/account/2fa/disable`: desativa o 2FA com senha (se houver) e um código.
- `POST /api/devices/register`: a câmera, logada na conta, se registra e recebe `deviceId` e `deviceSecret`.
- `GET /api/devices`: lista as câmeras da conta, o status online, a saúde do aparelho e o último movimento.
- `PATCH /api/devices/:id`: renomeia uma câmera da conta (nome obrigatório, até 40 caracteres).
- `DELETE /api/devices/:id`: remove uma câmera, revoga suas credenciais e encerra suas conexões; os eventos e fotos dela também são apagados.
- `GET /api/devices/:id/events`: últimos 20 movimentos da câmera.
- `GET /api/events/:id/snapshot`: foto de um movimento.
- `GET /api/push/key`: chave pública VAPID para assinar as notificações.
- `POST /api/push/subscribe` e `POST /api/push/unsubscribe`: ativa ou desativa os alertas neste navegador.
- `WS /ws/device?id=...`: conexão da câmera, autenticada pelo `deviceSecret`. Além dos quadros, recebe mensagens `health` e `motion`.
- `WS /ws/view?device=...`: conexão do visualizador da conta, que recebe os quadros JPEG, a saúde e os movimentos ao vivo. Usa o cookie de sessão.

A sessão da web fica num cookie `HttpOnly`, então o JavaScript da página não consegue lê-la. O app Android usa o token do corpo da resposta, no cabeçalho `Authorization: Bearer`.
