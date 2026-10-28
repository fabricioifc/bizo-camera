# App da câmera (Android)

Roda no celular que será a câmera. Faz a captura com CameraX e envia os quadros JPEG para o [servidor de retransmissão](../relay-server/README.md) quando há alguém assistindo.

## Compilar e instalar

1. Abra a pasta `camera-monitor` no Android Studio.
2. Em [app/build.gradle.kts](app/build.gradle.kts), troque `RELAY_URL` pelo endereço `https://` do seu servidor.
3. Conecte o celular com depuração USB e clique em Run.

## Uso

1. Toque em **Iniciar câmera** e conceda as permissões.
2. O app mostra um QR code e um código de 6 números.
3. Escaneie o QR code com o celular que vai assistir (ele abre o site e pareia sozinho).

## Configurações no Xiaomi Mi A3 (MIUI)

- *Configurações > Apps > Monitor de Câmera > Economia de bateria > Sem restrições*.
- *Segurança > Autoinicialização*: permita o app, se quiser que ele volte após reinício.
- Mantenha o celular ligado na tomada.
