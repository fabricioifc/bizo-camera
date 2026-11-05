import { ref } from 'vue';

// Instalação do site como app. O navegador avisa (beforeinstallprompt) quando dá para instalar;
// o aviso é guardado para o botão "Instalar" abrir a janela do navegador depois.

const standalone = () =>
  window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;

/** Já está aberto como app instalado. */
export const installed = ref(standalone());
/** O navegador permite instalar agora (Chrome, Edge, Samsung Internet...). */
export const canInstall = ref(false);
/** iPhone/iPad: instala pelo menu Compartilhar > Adicionar à Tela de Início. */
export const isIos = /iphone|ipad|ipod/i.test(navigator.userAgent)
  || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

let deferredPrompt = null;

window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault();
  deferredPrompt = event;
  canInstall.value = true;
});

window.addEventListener('appinstalled', () => {
  deferredPrompt = null;
  canInstall.value = false;
  installed.value = true;
});

window.matchMedia('(display-mode: standalone)').addEventListener?.('change', () => {
  installed.value = standalone();
});

/** Abre a janela de instalação do navegador. Devolve true se o usuário aceitou. */
export async function install() {
  if (!deferredPrompt) return false;
  const prompt = deferredPrompt;
  deferredPrompt = null;
  canInstall.value = false;
  await prompt.prompt();
  const { outcome } = await prompt.userChoice;
  return outcome === 'accepted';
}
