# App da câmera (Android)

Roda no celular que será a câmera. Faz a captura com CameraX e envia os quadros JPEG para o [servidor de retransmissão](../relay-server/README.md) quando há alguém assistindo.

## Compilar e instalar

1. Abra a pasta `camera-monitor` no Android Studio.
2. O endereço do servidor é `https://camera.bizo.dev.br` por padrão. Ele pode ser trocado no próprio app, sem gerar outro APK: no cartão de login (**Servidor: …**) ou em **Configurações > Servidor**. O app confere se o endereço responde antes de salvar e, com a câmera ligada, reconecta no endereço novo. Para mudar o padrão do build, passe a propriedade `relayUrl` ao Gradle:
   - Linha de comando: `./gradlew assembleRelease -PrelayUrl=https://outro.dominio`
   - Ou, para valer em todos os builds, adicione `relayUrl=https://outro.dominio` em `~/.gradle/gradle.properties` (no Windows, `C:\Users\<usuário>\.gradle\gradle.properties`).
3. O Client ID do Google (cliente **Aplicativo da Web**, o mesmo `GOOGLE_CLIENT_ID` do servidor) já está em `gradle.properties` como `googleServerClientId`. Para outro valor, use `-PgoogleServerClientId=...` na linha de comando.
4. No Google Cloud, crie um cliente OAuth do tipo **Android** com o nome do pacote `br.dev.bizo.cameramonitor` e o SHA-1 da keystore de release. Sem ele, o seletor de conta não abre. Para usar o build debug em paralelo, crie outro cliente Android com o pacote `br.dev.bizo.cameramonitor.debug` e o SHA-1 da chave de debug do Android.
5. Conecte o celular com depuração USB e clique em Run. O build debug tem o sufixo `.debug` no identificador do app e pode ficar instalado ao lado do release. Como são instalações separadas, configure novamente o endereço local do relay no app debug.
6. Ao trocar de servidor, o app apaga a sessão e o registro da câmera do servidor anterior; entre com o Google novamente no novo servidor.

## Testes

Os testes de unidade (detecção de movimento, limite por calor e formato do PIN) rodam no computador, sem celular:

```bash
./gradlew testDebugUnitTest
```

O Gradle precisa de um JDK entre 17 e 23. O Android Studio já traz um (por exemplo, `jbr-21` em `~/.jdks`); aponte o `JAVA_HOME` para ele se o terminal usar outra versão.

## Gerar uma versão (release)

1. Aumente `appVersionName` em `gradle.properties` (por exemplo, de `0.3.0` para `0.4.0`) e faça o commit. O `versionCode` é o número de commits do repositório, então cada release feito depois de um commit novo instala por cima do anterior.
2. Gere o APK assinado com a mesma keystore de sempre:
   - **Android Studio:** *Build > Generate Signed App Bundle / APK > APK*, escolha a keystore e o build **release**.
   - **Terminal:** crie `keystore.properties` na pasta `camera-monitor` (fora do git) com `storeFile`, `storePassword`, `keyAlias` e `keyPassword`, e rode `./gradlew assembleRelease`. O APK sai em `app/build/outputs/apk/release/app-release.apk`.
3. Publique: no site, em **Configurações > Atualização do app da câmera**, envie o `app-release.apk`. Os celulares avisam (notificação e aba Ajustes) e instalam com um toque em **Atualizar agora**; a câmera volta a transmitir sozinha depois. Na primeira vez, o Android pede para liberar o app em "Instalar apps desconhecidos".
   Sem o site (ou na primeira instalação), instale por cabo, por cima da versão anterior.

A versão aparece no fim da aba **Ajustes** (por exemplo, "versão 0.3.0 (17)").

**Diferenças do release para o debug:**

- O código passa pelo R8 (APK menor). As regras extras ficam em `app/proguard-rules.pro`.
- Os erros de login mostram só a mensagem, sem o motivo técnico (código HTTP, exceção).
- O endereço do servidor precisa ser `https://`.
- No build debug, para testar um servidor HTTP na rede local, informe o IP e a porta do computador (por exemplo, `192.168.1.11:8787`); o app usa `http://` automaticamente. O aparelho e o computador precisam estar na mesma rede.

## Organização do código

- `MainActivity`: tela única. Sem conta, mostra o login; com conta, a barra do topo e a navegação inferior com três abas. Pede o PIN para abrir as abas Câmera e Ajustes.
- `HomeFragment` (aba **Início**): status, prévia, iniciar/parar, aviso de bateria e resumo dos ajustes com atalhos.
- `CameraFragment` (aba **Câmera**): lente, qualidade e detecção de movimento.
- `SettingsFragment` (aba **Ajustes**): tema, PIN, servidor, conta e versão.
- `LoginController`: login com Google, código 2FA, configuração do 2FA e códigos de recuperação.
- `CameraStarter`: registra o aparelho e liga ou desliga o serviço da câmera.
- `AppUpdater`, `AppRelease`, `InstallResultReceiver`: atualização pelo próprio app (procura a versão publicada no servidor, baixa conferindo o sha256 e entrega ao instalador do Android).
- `RemoteSettings`: formato das configurações trocadas com o site (controle remoto).
- `ClipRecorder`, `ClipFrames`: vídeo curto do movimento (guarda 1 s antes, grava 7 s depois, codifica em MP4/H.264 com o encoder do celular).
- `AppTheme`, `CameraMonitorApp`: tema do sistema, claro ou escuro, aplicado ao abrir o app. As cores ficam em `res/values/colors.xml` (claro) e `res/values-night/colors.xml` (escuro).
- `StreamService`: serviço em primeiro plano que mantém a câmera e a conexão.
- `MotionDetector`, `ThermalLimit`, `DeviceHealth`, `FrameConversion`: peças usadas pelo serviço.
- `RelayClient`: login na API e conexão com o servidor.
- `CameraSettings`, `PinLock`, `PinDialogs`, `BootReceiver`: configurações, PIN e retomada após reiniciar.

## Uso

1. Toque em **Entrar com Google** e escolha a conta. Se a conta tiver verificação em duas etapas, digite o código do app autenticador. Se não tiver, configure o 2FA no próprio app, escaneando o QR code e guardando os códigos de recuperação.
2. Na aba **Início**, toque em **Iniciar câmera**, concedendo as permissões.
3. Abra o endereço do servidor no navegador do outro celular ou computador e entre com a mesma conta para assistir.

A aba **Câmera** muda lente, qualidade, rotação extra (a imagem já acompanha a posição do celular, em pé ou deitado; a rotação extra é para ajustes finos), lanterna e detecção de movimento (vale na hora, mesmo transmitindo). As mesmas opções podem ser mudadas pelo site, na tela da câmera ao vivo (controle remoto); a aba acompanha as mudanças. A aba **Ajustes** tem o tema (sistema, claro ou escuro), o PIN, o servidor e a conta.

## Configurações no Xiaomi Mi A3 (MIUI)

- *Configurações > Apps > Monitor de Câmera > Economia de bateria > Sem restrições*.
- *Segurança > Autoinicialização*: permita o app, se quiser que ele volte após reinício.
- Mantenha o celular ligado na tomada.

## Ícone e splash

O logotipo original fica em `branding/logo.webp`. Para trocar, substitua a imagem e gere os ícones de novo (precisa de Python com Pillow):

```bash
python branding/make_icons.py branding/logo.webp app/src/main/res branding/preview.png ../relay-server/frontend/public
```

O script tira o fundo branco, gera os ícones em todas as densidades (`mipmap-*`) e salva uma prévia do ícone redondo em `branding/preview.png`. A splash usa o mesmo desenho (`ic_launcher_foreground`). Com o último argumento, gera também o favicon e os ícones do site (`favicon.ico`, `icon-192.png` e `apple-touch-icon.png`).
