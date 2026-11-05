<script setup>
import { onMounted, ref } from 'vue';
import AppIcon from './AppIcon.vue';
import { errorText, post } from '../lib/api.js';
import { base64UrlToBytes, serviceWorker } from '../lib/push.js';

// Alertas de movimento por Web Push neste navegador.
const registration = ref(null);
const subscribed = ref(false);
const blocked = ref(false);
const busy = ref(false);
const error = ref('');

async function refresh() {
  if (!registration.value) return;
  subscribed.value = Boolean(await registration.value.pushManager.getSubscription());
  blocked.value = Notification.permission === 'denied';
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
  error.value = '';
  try {
    const current = await registration.value.pushManager.getSubscription();
    if (current) {
      await post('/api/push/unsubscribe', { endpoint: current.endpoint });
      await current.unsubscribe();
    } else if ((await Notification.requestPermission()) === 'granted') {
      error.value = await subscribe();
    }
  } catch {
    error.value = 'Não foi possível mudar os alertas neste navegador.';
  } finally {
    busy.value = false;
  }
  await refresh();
}

onMounted(async () => {
  registration.value = await serviceWorker();
  await refresh();
});
</script>

<template>
  <section v-if="registration" class="card alerts-card" aria-label="Alertas de movimento">
    <span class="alerts-icon"><AppIcon name="bell" /></span>
    <div class="alerts-text">
      <strong>Alertas de movimento</strong>
      <p v-if="error" class="small text-danger">{{ error }}</p>
      <p v-else-if="blocked" class="small muted">Bloqueados nas configurações do navegador.</p>
      <p v-else-if="subscribed" class="small muted">Ativados neste aparelho.</p>
      <p v-else class="small muted">Receba um aviso neste aparelho quando houver movimento.</p>
    </div>
    <button
      v-if="!blocked"
      type="button"
      class="btn"
      :class="subscribed ? 'btn-secondary' : 'btn-primary'"
      :disabled="busy"
      @click="toggle"
    >
      {{ subscribed ? 'Desativar' : 'Ativar alertas' }}
    </button>
  </section>
</template>
