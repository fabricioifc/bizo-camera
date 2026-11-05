<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import AlertsBar from '../components/AlertsBar.vue';
import AppIcon from '../components/AppIcon.vue';
import DeviceCard from '../components/DeviceCard.vue';
import PageHeader from '../components/PageHeader.vue';
import StatusMessage from '../components/StatusMessage.vue';
import { api } from '../lib/api.js';

const LIST_REFRESH_MS = 30_000;

// Câmera a abrir assim que a lista carregar (link de uma notificação). Volta a null depois de usada.
const openId = defineModel('openId', { type: String, default: null });
// open: assistir; connection: servidor respondeu ou não; unauthorized: sessão expirou; setup-required: falta o 2FA.
const emit = defineEmits(['open', 'connection', 'unauthorized', 'setup-required']);

const devices = ref([]);
const loaded = ref(false);
const loading = ref(false);
const error = ref('');
let timer = null;

const onlineCount = computed(() => devices.value.filter((device) => device.online).length);

async function load() {
  clearTimeout(timer);
  loading.value = true;
  try {
    const response = await api('/api/devices');
    emit('connection', true);
    if (response.status === 401) return emit('unauthorized');
    if (response.status === 403) return emit('setup-required');
    devices.value = (await response.json()).devices;
    loaded.value = true;
    error.value = '';
    const target = openId.value && devices.value.find((device) => device.id === openId.value);
    openId.value = null;
    if (target) return emit('open', target);
  } catch {
    emit('connection', false);
    error.value = 'Não foi possível atualizar a lista de câmeras.';
  } finally {
    loading.value = false;
  }
  timer = setTimeout(load, LIST_REFRESH_MS);
}

onMounted(load);
onBeforeUnmount(() => clearTimeout(timer));
</script>

<template>
  <PageHeader title="Câmeras">
    <template v-if="loaded" #subtitle>
      {{ devices.length ? `${onlineCount} de ${devices.length} online` : 'Nenhuma câmera registrada' }}
    </template>
    <template #actions>
      <button type="button" class="btn btn-secondary" :disabled="loading" @click="load">
        <AppIcon name="refresh" :size="16" />
        Atualizar
      </button>
    </template>
  </PageHeader>

  <AlertsBar />
  <StatusMessage :text="error" kind="error" />

  <div v-if="devices.length" class="devices">
    <DeviceCard
      v-for="device in devices"
      :key="device.id"
      :device="device"
      @open="$emit('open', $event)"
      @changed="load"
    />
  </div>

  <div v-else-if="loaded" class="card empty">
    <span class="empty-icon"><AppIcon name="camera" :size="28" /></span>
    <h2>Nenhuma câmera ainda</h2>
    <p class="muted">Instale o app Monitor de Câmera num celular Android e entre com esta mesma conta Google. A câmera aparece aqui sozinha.</p>
  </div>
</template>
