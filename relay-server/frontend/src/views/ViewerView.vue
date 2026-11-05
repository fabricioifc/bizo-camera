<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import HealthInfo from '../components/HealthInfo.vue';
import MotionEvents from '../components/MotionEvents.vue';
import { api } from '../lib/api.js';

const RECONNECT_MS = 3000;

const props = defineProps({ device: { type: Object, required: true } });
// back: voltar à lista; rejected: o servidor recusou o acesso à câmera.
const emit = defineEmits(['back', 'rejected']);

const health = ref(props.device.health);
const status = ref('Conectando…');
const frameUrl = ref('');
const events = ref([]);
const eventsLoaded = ref(false);

let socket = null;
let retryTimer = null;
let closed = false;

function showFrame(data) {
  if (frameUrl.value) URL.revokeObjectURL(frameUrl.value);
  frameUrl.value = URL.createObjectURL(new Blob([data], { type: 'image/jpeg' }));
  status.value = '';
}

function handleMessage(text) {
  let message;
  try {
    message = JSON.parse(text);
  } catch {
    return;
  }
  if (message.type === 'status') {
    if (!message.online) {
      status.value = 'Câmera offline. Aguardando conexão…';
      health.value = null;
    } else if (!frameUrl.value) {
      status.value = 'Conectado. Aguardando imagem…';
    }
  } else if (message.type === 'health') {
    health.value = message.health;
  } else if (message.type === 'motion') {
    events.value.unshift(message.event);
    eventsLoaded.value = true;
  }
}

function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${scheme}://${location.host}/ws/view?device=${encodeURIComponent(props.device.id)}`);
  socket.binaryType = 'arraybuffer';
  socket.onmessage = (event) => {
    if (typeof event.data === 'string') handleMessage(event.data);
    else showFrame(event.data);
  };
  socket.onclose = (event) => {
    if (closed) return;
    if (event.code === 1008) {
      emit('rejected');
      return;
    }
    status.value = 'Conexão perdida. Reconectando…';
    retryTimer = setTimeout(() => {
      if (!closed) connect();
    }, RECONNECT_MS);
  };
}

async function loadEvents() {
  try {
    const response = await api(`/api/devices/${encodeURIComponent(props.device.id)}/events`);
    if (!response.ok || closed) return;
    // Movimentos que chegaram pelo socket antes da lista ficam na frente.
    const live = events.value;
    const known = new Set(live.map((event) => event.id));
    events.value = [...live, ...(await response.json()).events.filter((event) => !known.has(event.id))];
    eventsLoaded.value = true;
  } catch {
    // Os eventos são carregados de novo quando a câmera for aberta.
  }
}

onMounted(() => {
  connect();
  loadEvents();
});

onBeforeUnmount(() => {
  closed = true;
  clearTimeout(retryTimer);
  if (socket) socket.close();
  if (frameUrl.value) URL.revokeObjectURL(frameUrl.value);
});
</script>

<template>
  <section>
    <div class="viewer-top">
      <button type="button" class="secondary" @click="$emit('back')">← Voltar</button>
      <strong>{{ device.name }}</strong>
      <HealthInfo :health="health" />
    </div>
    <div class="stage">
      <img v-if="frameUrl" id="frame" :src="frameUrl" alt="Transmissão ao vivo">
      <p v-else class="hint">{{ status }}</p>
    </div>
    <MotionEvents :events="events" :loaded="eventsLoaded" />
  </section>
</template>
