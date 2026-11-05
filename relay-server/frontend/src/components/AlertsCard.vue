<script setup>
import { onMounted, ref } from 'vue';
import AppIcon from './AppIcon.vue';
import { errorText, post } from '../lib/api.js';
import { base64UrlToBytes, serviceWorker } from '../lib/push.js';

// Alertas de movimento por Web Push neste navegador.
const registration = ref(null);
const ready = ref(false);
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
  await refresh().catch(() => {});
  ready.value = true;
});
</script>

<template>
  <section class="card" aria-labelledby="alerts-title">
    <div class="card-header">
      <div>
        <h2 id="alerts-title">Alertas de movimento</h2>
        <p class="small muted">Notificação neste aparelho quando uma câmera detectar movimento.</p>
      </div>
    </div>
    <div class="setting">
      <span class="setting-icon"><AppIcon name="bell" /></span>
      <div class="setting-text">
        <template v-if="!ready">Verificando…</template>
        <template v-else-if="!registration">Este navegador não aceita notificações.</template>
        <template v-else-if="blocked">Bloqueados nas configurações do navegador.</template>
        <template v-else-if="subscribed">Ativados neste aparelho.</template>
        <template v-else>Desativados neste aparelho.</template>
        <p v-if="error" class="small text-danger">{{ error }}</p>
      </div>
      <button
        v-if="registration && !blocked"
        type="button"
        class="btn"
        :class="subscribed ? 'btn-secondary' : 'btn-primary'"
        :disabled="busy"
        @click="toggle"
      >
        {{ subscribed ? 'Desativar' : 'Ativar' }}
      </button>
    </div>
  </section>
</template>
