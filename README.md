# Monitor de Câmera

Transforma um celular antigo em câmera de segurança e permite assisti-la de qualquer lugar, pelo navegador, via Wi-Fi, 3G ou LTE.

## Partes do projeto

- [camera-monitor](camera-monitor): app Android que roda no celular da câmera (fixado no local).
- [relay-server](relay-server): servidor que liga a câmera aos visualizadores, e também hospeda o site de visualização.

## Como funciona

1. A câmera abre uma conexão de saída com o servidor. Não é preciso abrir portas no roteador.
2. No celular da câmera, o app mostra um QR code.
3. No seu celular principal, você escaneia o QR code. O site abre e pareia a câmera automaticamente (ou você digita o código de 6 números).
4. Ao tocar em "Assistir", o servidor pede à câmera para começar a transmitir. Quando ninguém está assistindo, a transmissão para.

## Ordem de execução

1. Publique o `relay-server` (veja [relay-server/README.md](relay-server/README.md)) e anote o endereço `https://...`.
2. Coloque esse endereço em `RELAY_URL` no arquivo [camera-monitor/app/build.gradle.kts](camera-monitor/app/build.gradle.kts) e instale o app no Mi A3.
3. No Mi A3, em *Configurações > Apps > Monitor de Câmera > Economia de bateria*, escolha *Sem restrições*.
4. No seu celular principal, abra o endereço do servidor e adicione a câmera.

## Segurança

- Use sempre HTTPS/WSS em produção. Sem isso, o token e o vídeo trafegam sem criptografia.
- Quem tiver acesso ao site paireado vê a câmera. Não compartilhe o navegador ou o token salvo.
- O servidor guarda apenas hashes de segredos e tokens.

## Próximos passos

- Login com conta Google, para parear sem QR code. Isso exige um projeto no Google Cloud que você precisa criar.
- Detecção de movimento com notificação push.
- Gravação de clipes quando houver movimento.
