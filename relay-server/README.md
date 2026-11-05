# Servidor de retransmissão (relay)

Liga a câmera (app Android) aos visualizadores (navegador). Também entrega o site de visualização em `/`.

| Pasta | O que é |
|---|---|
| `backend/` | Servidor Node: API, WebSockets da câmera e dos visualizadores, e a página já compilada. Porta **8787**. |
| `frontend/` | Página em Vue 3 + Vite. Em produção vira arquivos estáticos (`frontend/dist`) servidos pelo backend. |
| `scripts/` | Backup e restauração do volume de dados, usados pelo Docker. |

**Portas.** Em produção só existe a **8787**: o backend serve a página, a API e a câmera. A **5173** é o servidor do Vite e só existe em desenvolvimento, para recarregar a página a cada alteração; ele encaminha a API e os WebSockets para a 8787. Por isso, em desenvolvimento, as duas ficam no ar: abra a página na 5173 e aponte o app Android para a 8787. Em desenvolvimento, a 8787 não serve a página.

## Rodar com Docker

Requer Docker Desktop (ou Docker Engine + Compose). O `Dockerfile` compila o frontend e gera uma imagem só com o backend e a página pronta.

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

O `PUBLIC_URL` do `.env` é usado para construir o callback OAuth. Para desenvolver sem alterar o endereço de produção, use o arquivo Compose de desenvolvimento:

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

Esse arquivo Compose é independente, desativa a exigência de 2FA, publica a porta `8787` nas interfaces de rede do computador e usa um volume separado (`relay-data-dev`). Também inicia o frontend Vue/Vite em um container separado na porta `5173`. Ele usa `PUBLIC_URL=http://localhost:5173` para que o callback Google passe pelo Vite. Adicione `http://localhost:5173/api/auth/google/callback` às URIs de redirecionamento autorizadas do cliente OAuth local.

Abra `http://localhost:5173` para usar a página. O backend e a conexão da câmera continuam na porta `8787`; use `http://IP_DO_COMPUTADOR:8787` no app Android debug. Os dois lados recarregam sozinhos, sem rebuild:

- **frontend/**: o Vite atualiza a página aberta a cada alteração salva.
- **backend/src/**: o nodemon reinicia o servidor a cada alteração salva (as câmeras e as páginas abertas reconectam sozinhas).

Como a pasta do Windows montada no Docker não avisa o Linux das alterações, os dois conferem os arquivos periodicamente (`VITE_POLLING=1` no Vite, `--legacy-watch` no nodemon). Use `--build` só quando mudar dependências (`package.json`) ou um `Dockerfile.dev`.

Para conectar o celular na mesma rede Wi-Fi:

1. Descubra o endereço IPv4 local do computador que está rodando Docker (por exemplo, `192.168.1.20`).
2. No app Android **debug**, abra **Servidor** na tela de login ou em **Configurações > Servidor** e informe `http://192.168.1.20:8787`, substituindo pelo IP encontrado.
3. Se o Android não conseguir conectar, permita conexões de entrada na porta `8787` para a rede local no firewall do computador.

O build release do app exige HTTPS; use o build debug para testar por HTTP na rede local. Para voltar à configuração normal, execute `docker compose up -d --build relay`; o Compose de produção continua publicado somente em `127.0.0.1`.

Comandos úteis:

```bash
docker compose logs -f relay     # acompanhar o log
docker compose restart relay     # reiniciar
docker compose down              # parar (mantém os dados)
```

Para apagar os dados junto com o volume, use `docker compose down -v`. Isso remove todas as contas.

### Desenvolvimento do frontend sem Docker

Com o backend rodando na 8787 (pelo Compose de desenvolvimento, ou com `npm run dev` em `backend/`, que reinicia a cada alteração):

```bash
cd relay-server/frontend
npm install
npm run dev
```

Abra `http://localhost:5173`. O Vite encaminha a API, o retorno do login com Google e os WebSockets para a 8787.

## Rodar sem Docker

```bash
cd relay-server/frontend
npm install
npm run build
cd ../backend
npm install
npm start
```

Abra http://localhost:8787 no navegador.

## Testes

Os testes ficam em `backend/tests/` e usam o executor do próprio Node (`node --test`). Cada arquivo sobe o servidor de verdade numa porta livre, com um banco temporário: login e sessões, 2FA, login com Google (com chave falsa), saúde, movimento e gerenciamento das câmeras, permissões das fotos, alertas e arquivos da página. Antes, o `npm test` compila o frontend, porque um dos testes confere a página servida.

Num container, sem precisar de Node instalado (PowerShell ou Git Bash, na pasta `relay-server`):

```bash
docker run --rm -v "${PWD}:/app" -v /app/backend/node_modules -v /app/frontend/node_modules -w /app node:20-alpine sh -c "cd frontend && npm ci && cd ../backend && npm ci && npm test"
```

Com Node instalado: `npm install` em `frontend/` e em `backend/`, depois `npm test` em `backend/`.

## Organização do código

```
backend/
  src/
    server.js      ponto de entrada: lê a configuração e abre a porta
    config.js      variáveis de ambiente
    app.js         monta banco, serviços, rotas e WebSockets
    websocket.js   /ws/device (câmera) e /ws/view (visualizador)
    http/          resposta JSON, roteador, limite de tentativas, arquivos da página
    routes/        rotas da API: auth, account (2FA), devices, push, e as verificações de sessão (guards)
    services/      regras: sessões, contas, desafio do 2FA, câmeras e movimentos, alertas, conexões ao vivo (hub)
    lib/           peças sem regra de negócio: banco JSON, TOTP, Google OIDC, Web Push, hashes
  tests/
frontend/
  index.html
  public/          service worker e ícones, copiados como estão para dist/
  src/
    App.vue        navegação entre as telas e estado da sessão
    layouts/       AuthLayout (cartão centralizado, sem login) e AppLayout (barra lateral; gaveta no celular)
    views/         telas: login, código 2FA, câmeras, câmera ao vivo, segurança, configurações
    components/    barra lateral, cabeçalho de tela, ícones, tema, cartão da câmera, cartão de alertas, movimentos
    lib/           chamadas à API, formatação, Web Push, tema claro/escuro
    styles.css     tokens de cor dos dois temas e todo o visual
  vite.config.js
```

## Mais documentação

- [Implantação e operação](../docs/implantacao.md): variáveis de ambiente, túnel, Dokploy, backup, login com Google e 2FA.
- [Funcionalidades](../docs/funcionalidades.md): controle remoto, vídeo, alertas, atualização do app e instalação do site.
- [API](../docs/api.md): rotas HTTP e WebSocket.
