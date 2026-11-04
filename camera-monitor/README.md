# App da câmera (Android)

Roda no celular que será a câmera. Faz a captura com CameraX e envia os quadros JPEG para o [servidor de retransmissão](../relay-server/README.md) quando há alguém assistindo.

## Compilar e instalar

1. Abra a pasta `camera-monitor` no Android Studio.
2. O endereço do servidor é `https://camera.bizo.dev.br` por padrão. Para usar outro, passe a propriedade `relayUrl` ao Gradle:
   - Linha de comando: `./gradlew assembleRelease -PrelayUrl=https://outro.dominio`
   - Ou, para valer em todos os builds, adicione `relayUrl=https://outro.dominio` em `~/.gradle/gradle.properties` (no Windows, `C:\Users\<usuário>\.gradle\gradle.properties`).
3. Conecte o celular com depuração USB e clique em Run.

## Uso

1. Abra o app, entre com e-mail e senha da conta. Se a conta tiver verificação em duas etapas, digite o código do app autenticador quando ele pedir.
2. Toque em **Iniciar câmera**, concedendo as permissões.
2. Abra o endereço do servidor no navegador do outro celular ou computador e entre com a mesma conta para assistir.

## Configurações no Xiaomi Mi A3 (MIUI)

- *Configurações > Apps > Monitor de Câmera > Economia de bateria > Sem restrições*.
- *Segurança > Autoinicialização*: permita o app, se quiser que ele volte após reinício.
- Mantenha o celular ligado na tomada.
