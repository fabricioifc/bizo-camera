# API do servidor

Rotas HTTP e WebSocket do `camera-web`.

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
- `GET /api/devices`: lista as câmeras da conta, o status online, a saúde do aparelho, o último movimento, desde quando está offline (`lastSeenAt`) e a versão do app (`app`, com `update` quando há versão publicada mais nova).
- `PATCH /api/devices/:id`: renomeia uma câmera da conta (nome obrigatório, até 40 caracteres).
- `DELETE /api/devices/:id`: remove uma câmera, revoga suas credenciais e encerra suas conexões; os eventos e fotos dela também são apagados.
- `GET /api/devices/:id/events`: eventos da câmera, do mais novo para o mais antigo (até 200; `kind`: `motion` ou `photo`).
- `DELETE /api/devices/:id/events`: exclusão em lote, com corpo `{ "ids": [...] }`; responde `{ removed }`.
- `POST /api/devices/:id/photo`: "Foto agora"; espera a câmera e responde com o evento da foto (409 se a câmera estiver offline ou ocupada, 504 se não responder).
- `GET /api/events/:id/snapshot`: foto de um movimento.
- `POST /api/app/release`: publica uma versão do app (corpo = o APK). Responde com pacote, versão, tamanho e sha256.
- `GET /api/app/latest`: versão publicada (`packageName`, `versionCode`, `versionName`, `size`, `sha256`), ou 404.
- `GET /api/app/download`: o APK publicado.
- `GET /api/events/:id/clip`: vídeo do movimento (MP4, aceita `Range`). Com `?download=1`, vem como arquivo para baixar; o mesmo vale para `/snapshot`.
- `DELETE /api/events/:id`: remove o evento, com foto e vídeo.
- `GET /api/push/key`: chave pública VAPID para assinar as notificações.
- `POST /api/push/subscribe` e `POST /api/push/unsubscribe`: ativa ou desativa os alertas neste navegador.
- `WS /ws/device?id=...`: conexão da câmera, autenticada pelo `deviceSecret`. Além dos quadros, recebe mensagens `health`, `motion` e `settings` (estado atual das configurações) e envia à câmera `start`, `stop`, `configure` (mudanças pedidas pelo site) e `focus` (`mode`: `lock`, `far` ou `auto`).
- `WS /ws/view?device=...`: conexão do visualizador da conta, que recebe os quadros JPEG, a saúde, as configurações e os movimentos ao vivo, e envia `configure` e `focus` para o controle remoto. Usa o cookie de sessão.

A sessão da web fica num cookie `HttpOnly`, então o JavaScript da página não consegue lê-la. O app Android usa o token do corpo da resposta, no cabeçalho `Authorization: Bearer`.
