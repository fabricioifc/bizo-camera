# Servidor de retransmissão (relay)

Liga a câmera (app Android) aos visualizadores (navegador). Também entrega o site de visualização em `/`.

## Rodar com Docker

Requer Docker Desktop (ou Docker Engine + Compose).

```bash
cd relay-server
docker compose up -d --build
```

O servidor fica em `http://localhost:8787`, publicado só em `127.0.0.1`. Os dados (usuários, câmeras e sessões) ficam no volume `relay-data`, então sobrevivem a rebuilds e reinícios.

Comandos úteis:

```bash
docker compose logs -f relay     # acompanhar o log
docker compose restart relay     # reiniciar
docker compose down              # parar (mantém os dados)
```

Para apagar os dados junto com o volume, use `docker compose down -v`. Isso remove todas as contas.

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

No Docker, coloque `GOOGLE_CLIENT_ID` e `GOOGLE_CLIENT_SECRET` no arquivo `relay-server/.env`. Ele é lido pelo `docker-compose.yml` e está no `.gitignore`.

## Login com Google

1. No [Google Cloud Console](https://console.cloud.google.com/apis/credentials), crie um cliente OAuth do tipo **Aplicativo da Web**.
2. Em **URIs de redirecionamento autorizados**, adicione:
   `https://camera.bizo.dev.br/api/auth/google/callback`
3. Copie o **Client ID** e o **Client secret** para o `.env` e reinicie o relay com `docker compose up -d`.

A conta Google só é vinculada a uma conta existente com o mesmo e-mail quando o Google confirma o e-mail (`email_verified`). Se a conta já tiver verificação em duas etapas, ela continua sendo pedida depois do Google.

## Verificação em duas etapas (2FA)

- Em **Segurança**, na tela da lista de câmeras, ative o 2FA. O servidor mostra um QR code para o app autenticador (Google Authenticator, Authy, 1Password…).
- Ao confirmar o código, aparecem 8 **códigos de recuperação**. Eles valem uma vez cada e não são mostrados de novo.
- Com o 2FA ativo, o login pede o código depois da senha. Depois de 5 códigos errados, é preciso entrar com a senha de novo.
- Para desativar, informe a senha e um código (do app ou de recuperação).

## Proteção contra tentativas

- Depois de 5 senhas erradas, a conta fica bloqueada por 15 minutos.
- Login, cadastro e códigos 2FA têm limite de tentativas por IP, por minuto.
- Cada código TOTP vale uma vez e códigos de recuperação são apagados depois de usados.

## Endpoints

- `GET /api/config`: diz se o cadastro está aberto (`signupOpen`) e se o Google está ativo (`googleEnabled`).
- `POST /api/auth/signup`: cria conta com `email` e `password` (mínimo de 8 caracteres).
- `POST /api/auth/login`: entra com `email` e `password`. Se houver 2FA, responde `{ mfaRequired: true, mfaToken }`.
- `POST /api/auth/mfa`: segunda etapa do login, com `mfaToken` e `code` (app ou recuperação).
- `POST /api/auth/logout`: encerra a sessão.
- `GET /api/auth/me`: dados da conta logada (`email`, `hasPassword`, `google`, `twoFactor`).
- `GET /api/auth/google/start` e `GET /api/auth/google/callback`: fluxo do login com Google.
- `POST /api/account/2fa/setup`: gera o segredo do 2FA e o QR code.
- `POST /api/account/2fa/enable`: confirma o código e devolve os códigos de recuperação.
- `POST /api/account/2fa/disable`: desativa o 2FA com senha (se houver) e um código.
- `POST /api/devices/register`: a câmera, logada na conta, se registra e recebe `deviceId` e `deviceSecret`.
- `GET /api/devices`: lista as câmeras da conta e o status online.
- `WS /ws/device?id=...`: conexão da câmera, autenticada pelo `deviceSecret`.
- `WS /ws/view?device=...`: conexão do visualizador da conta, que recebe os quadros JPEG. Usa o cookie de sessão.

A sessão da web fica num cookie `HttpOnly`, então o JavaScript da página não consegue lê-la. O app Android usa o token do corpo da resposta, no cabeçalho `Authorization: Bearer`.
