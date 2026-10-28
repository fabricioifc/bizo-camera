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
| `DATA_FILE` | `data/db.json` | Arquivo onde ficam usuários, câmeras e sessões. No Docker, é `/data/db.json`. |
| `ALLOW_SIGNUP` | (vazio) | Defina `1` para permitir novos cadastros além do primeiro usuário. |
| `TRUST_PROXY` | (vazio) | Defina `1` atrás de um proxy ou túnel (cloudflared, Caddy, Nginx), para limitar tentativas por IP real. |

## Endpoints

- `GET /api/config`: diz se o cadastro está aberto (`signupOpen`).
- `POST /api/auth/signup`: cria conta com `email` e `password` (mínimo de 8 caracteres).
- `POST /api/auth/login`: entra com `email` e `password` e devolve um token de sessão.
- `POST /api/auth/logout`: encerra a sessão.
- `POST /api/devices/register`: a câmera, logada na conta, se registra e recebe `deviceId` e `deviceSecret`.
- `GET /api/devices`: lista as câmeras da conta e o status online.
- `WS /ws/device?id=...`: conexão da câmera, autenticada pelo `deviceSecret`.
- `WS /ws/view?token=...&device=...`: conexão do visualizador da conta, que recebe os quadros JPEG.
