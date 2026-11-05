<script setup>
import { onMounted, ref } from 'vue';
import { errorText, post } from '../lib/api.js';
import { base64UrlToBytes, serviceWorker } from '../lib/push.js';

// Alertas de movimento por Web Push neste navegador.
const registration = ref(null);
const text = ref('');
const buttonText = ref('');
const blocked = ref(false);
const busy = ref(false);

async function refresh() {
  if (!registration.value) return;
  const subscription = await registration.value.pushManager.getSubscription();
  blocked.value = Notification.permission === 'denied';
  if (blocked.value) {
    text.value = 'Alertas bloqueados nas configurações do navegador.';
  } else if (subscription) {
    text.value = 'Alertas de movimento ativados neste aparelho.';
    buttonText.value = 'Desativar';
  } else {
    text.value = 'Receba um aviso neste aparelho quando houver movimento.';
    buttonText.value = 'Ativar alertas';
  }
}

async function subscribe() {
  const { publicKey } = await (await fetch('/api/push/key')).json();
  const subscription = await registration.value.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: base64UrlToBytes(publicKey),
  });
  const response = await post('/api/push/subscribe', subscription.toJSON());
  if (response.ok) return '';
  await subscription.unsubscribe();
  return errorText(response);
}

async function toggle() {
  busy.value = true;
  let error = '';
  try {
    const current = await registration.value.pushManager.getSubscription();
    if (current) {
      await post('/api/push/unsubscribe', { endpoint: current.endpoint });
      await current.unsubscribe();
    } else if ((await Notification.requestPermission()) === 'granted') {
      error = await subscribe();
    }
  } catch {
    error = 'Não foi possível mudar os alertas neste navegador.';
  } finally {
    busy.value = false;
  }
  await refresh();
  if (error) text.value = error;
}

onMounted(async () => {
  registration.value = await serviceWorker();
  await refresh();
});
</script>

<template>
  <div v-if="registration" class="alerts">
    <span class="hint">{{ text }}</span>
    <button type="button" class="secondary" :hidden="blocked" :disabled="busy" @click="toggle">{{ buttonText }}</button>
  </div>
</template>
