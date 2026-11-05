<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import AlertsBar from '../components/AlertsBar.vue';
import DeviceCard from '../components/DeviceCard.vue';
import { api } from '../lib/api.js';

const LIST_REFRESH_MS = 30_000;

// Câmera a abrir assim que a lista carregar (link de uma notificação). Volta a null depois de usada.
const openId = defineModel('openId', { type: String, default: null });
// open: assistir; connection: servidor respondeu ou não; unauthorized: sessão expirou; setup-required: falta o 2FA.
const emit = defineEmits(['open', 'connection', 'unauthorized', 'setup-required']);

const devices = ref([]);
const error = ref('');
let timer = null;

async function load() {
  clearTimeout(timer);
  try {
    const response = await api('/api/devices');
    emit('connection', true);
    if (response.status === 401) return emit('unauthorized');
    if (response.status === 403) return emit('setup-required');
    devices.value = (await response.json()).devices;
    error.value = '';
    const target = openId.value && devices.value.find((device) => device.id === openId.value);
    openId.value = null;
    if (target) return emit('open', target);
  } catch {
    emit('connection', false);
    error.value = 'Não foi possível atualizar a lista de câmeras.';
  }
  timer = setTimeout(load, LIST_REFRESH_MS);
}

onMounted(load);
onBeforeUnmount(() => clearTimeout(timer));
</script>

<template>
  <section>
    <AlertsBar />
    <div class="cards">
      <DeviceCard
        v-for="device in devices"
        :key="device.id"
        :device="device"
        @open="$emit('open', $event)"
        @changed="load"
      />
    </div>
    <p v-if="!devices.length && !error" class="hint">Nenhuma câmera registrada nesta conta. Faça login no app da câmera.</p>
    <p v-if="error" class="hint error">{{ error }}</p>
  </section>
</template>
