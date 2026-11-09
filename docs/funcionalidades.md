# Funcionalidades

Como cada recurso funciona, no app da câmera e no site. Para instalar e publicar, veja a [implantação](implantacao.md).

## Controle remoto

A tela de uma câmera tem duas abas: **Ao vivo** (imagem e controles) e **Movimentos** (fotos dos últimos movimentos, com um contador dos que chegaram enquanto a aba estava fechada). Na aba Ao vivo, o cartão **Controles** muda, sem tocar no celular:

- câmera (traseira ou frontal), zoom, rotação extra (−90°, 0°, 90° ou 180°) e qualidade. A imagem já sai na posição do celular (em pé ou deitado), lida pelo sensor; a rotação extra é só para casos em que o sensor não ajuda, como o celular deitado de costas ou preso no teto;
- lanterna (só em lentes com flash; a frontal normalmente não tem);
- modo noturno;
- detecção de movimento e sensibilidade.

O pedido vai pela conexão ao vivo do visualizador. O servidor só repassa valores conhecidos, com no máximo 20 pedidos a cada 10 segundos por visualizador. A câmera aplica, salva no aparelho e responde com o estado atual, que aparece em todos os visualizadores abertos. Precisa da versão do app com controle remoto; com a versão anterior, o cartão fica esperando a câmera.

## Foco

O autofoco roda sozinho, mas no escuro ele pode ficar procurando e deixar a imagem desfocada. No cartão **Controles**, **Focar agora** foca no que está no centro da imagem e mantém esse foco fixo, sem ficar procurando. Ele continua fixo até você tocar em **Voltar ao automático** ou a câmera religar (trocar lente, qualidade ou modo noturno, por exemplo).

**Focar longe** deixa a lente no infinito, com o autofoco desligado. É a melhor opção para filmar a rua ou o quintal à noite ou atrás de vidro, onde o autofoco não enxerga contraste ou mira o reflexo. Essa escolha fica salva no celular e volta sozinha quando a câmera religa. Objetos muito perto da lente ficam desfocados, e nem todo celular aceita esse ajuste.

A mudança de imagem durante o foco não conta como movimento. Se não conseguir focar, a câmera avisa no site e continua no automático.

## Zoom

O zoom fica no app (aba **Câmera**) e no site (cartão **Controles**), e a faixa é a do celular. De 1× para cima, é o zoom da câmera principal. Abaixo de 1× (por exemplo, 0,6×), o app usa a câmera traseira mais aberta, a grande-angular, sem interromper a transmissão.

O 0,6× só aparece se o celular liberar a grande-angular para outros apps. Vários aparelhos, como os da Xiaomi, reservam essa câmera para o app de câmera do fabricante; nesse caso, o zoom começa em 1× e a tela explica o motivo.

## Modo noturno

A chave **Modo noturno** fica no app (aba **Câmera**) e no site (cartão **Controles**). Ligada, ela faz duas coisas:

- **Clareia a imagem no escuro:** deixa os quadros por segundo caírem, para cada quadro ficar mais tempo exposto, e aumenta a compensação de exposição para dois terços do máximo do celular. O ganho real depende do aparelho. Nos primeiros 3 segundos depois de ligar a câmera, a imagem clareando não conta como movimento.
- **Apaga a tela:** com a câmera transmitindo e o app aberto, a tela fica preta, com brilho mínimo e sem as barras do sistema, depois de 15 segundos sem toque. Um toque mostra o app de novo. A câmera também continua funcionando se o Android apagar a tela sozinho.

## Vídeo do movimento

Com **Gravar vídeo do movimento** ligado (no app ou nos controles do site), a câmera grava cerca de 30 segundos a cada movimento: 1 segundo antes e 29 depois, a 8 quadros por segundo e até 480 px no lado maior, em MP4 (H.264). A foto e a notificação saem na hora; o vídeo chega assim que termina de gravar e fica no mesmo evento. Um movimento que acontece enquanto um vídeo ainda está sendo gravado fica só com a foto.

A aba **Movimentos** é uma linha do tempo:

- filtro por tipo: **Tudo**, **Movimentos**, **Vídeos** ou **Fotos**;
- os dias com registros, com a contagem de cada um (o contador da aba mostra só os de hoje); escolhendo um dia, aparece a atividade hora a hora, e tocar numa hora filtra só aquela hora;
- a lista agrupada por dia; tocar num item abre o vídeo (ou a foto), com **Baixar** e **Remover**;
- **Selecionar** marca o que excluir: toque em cada item, em **Marcar o dia** para pegar um dia inteiro ou em **Marcar tudo** para pegar o que o filtro está mostrando. **Excluir N** apaga o que está marcado, com confirmação. Trocar o filtro limpa a marcação.

Espaço: além do limite de 200 eventos por câmera, os vídeos de todas as câmeras somam no máximo `CLIP_STORAGE_MB` (padrão 500 MB). Acima disso, os vídeos mais antigos são apagados; o evento e a foto continuam.

## Foto agora

O botão de câmera ao lado de **Assistir**, na lista, e **Foto agora**, na aba Movimentos, pedem à câmera uma foto em qualidade alta (até 1920 px no lado maior), sem abrir o vídeo. A foto aparece numa janela e fica guardada em Movimentos com a etiqueta **Foto**, sem gerar alerta e sem contar como último movimento. Uma foto por vez por câmera; a câmera tem 20 segundos para responder (apps anteriores a este recurso não respondem).

## Alertas de movimento

- O app da câmera detecta movimento e envia um evento com uma foto. O servidor aceita no máximo um alerta por câmera a cada 30 segundos e guarda os 200 eventos mais recentes de cada câmera.
- As fotos ficam em `snapshots/`, ao lado do banco (no Docker, dentro do volume `relay-data`). Só o dono da câmera consegue abri-las.
- Para receber notificações, abra **Configurações** no menu e toque em **Ativar** em Alertas de movimento, em cada navegador ou celular onde quer o aviso. O navegador pede permissão.
- As notificações usam Web Push. As chaves VAPID são geradas na primeira execução e salvas em `vapid.json`, também ao lado do banco. Se esse arquivo for apagado, os alertas precisam ser ativados de novo em cada aparelho.
- No iPhone, as notificações só funcionam com o site adicionado à tela de início (iOS 16.4 ou mais novo).

## Alertas da câmera

Além do movimento, o servidor avisa pela mesma notificação do navegador (ative em **Configurações > Alertas**, em cada aparelho):

- **Câmera offline:** sem conexão por 5 minutos (`OFFLINE_ALERT_MS`); quedas rápidas de Wi-Fi não contam. Quando ela volta, chega "Câmera de volta".
- **Fora da tomada:** o celular parou de carregar.
- **Bateria baixa:** 20% ou menos, sem carregar. Avisa de novo só depois de recarregar.
- **Esquentando:** o app reduziu a qualidade por causa do calor.

O mesmo aviso (tomada, bateria, calor) não se repete em menos de 10 minutos. Ao subir, o servidor dá 5 minutos para as câmeras vistas nas últimas 24 horas reconectarem; se não voltarem, avisa. A lista de câmeras mostra desde quando cada câmera está offline.

## Atualização do app da câmera

Em **Configurações**, o cartão **Atualização do app da câmera** recebe o APK de release (até 64 MB). O servidor lê o pacote e a versão de dentro do APK, recusa APKs de outros apps e guarda só a última versão em `releases/`, ao lado do banco (no Docker, dentro do volume, então entra no backup).

Os celulares com o app procuram versão nova ao abrir o app, ao abrir a aba Ajustes e, com a câmera ligada, a cada 6 horas (com notificação). A instalação é feita pelo instalador do Android, que pede confirmação. O APK precisa ser assinado com a mesma chave do app instalado; senão, o Android recusa.

Cada câmera informa a versão do app junto com a saúde do aparelho. A lista de câmeras mostra a versão de cada celular (mesmo offline) e o aviso **Atualização pendente** quando há uma versão publicada mais nova do mesmo pacote. Câmeras com app anterior a este recurso aparecem sem versão até serem atualizadas.

`APP_PACKAGE` define o pacote aceito (padrão `br.dev.bizo.cameramonitor`). O Compose de desenvolvimento aceita o build debug (`br.dev.bizo.cameramonitor.debug`).

## Instalar como app

O site é instalável (PWA): no Chrome/Edge do Android, Windows ou macOS, aparece **Instalar app** na barra lateral e em **Configurações > Instalar como app** (ou no menu do navegador). No iPhone, é pelo Safari: **Compartilhar > Adicionar à Tela de Início**; lá, os alertas só funcionam com o site instalado.

Instalado, abre em tela cheia com o ícone da Buba e, sem rede, mostra a última versão da página (com "Sem conexão") em vez de uma página de erro. O service worker (`sw.js`) guarda só a página e os arquivos do build; a API, as fotos e os vídeos sempre vêm do servidor. A instalação exige HTTPS (ou `localhost`).
