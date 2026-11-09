<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import AppIcon from '../components/AppIcon.vue';
import CameraControls from '../components/CameraControls.vue';
import HealthInfo from '../components/HealthInfo.vue';
import MotionEvents from '../components/MotionEvents.vue';
import PageHeader from '../components/PageHeader.vue';
import EventDialog from '../components/EventDialog.vue';
import StatusMessage from '../components/StatusMessage.vue';
import { api, takePhoto } from '../lib/api.js';

const RECONNECT_MS = 3000;

const props = defineProps({ device: { type: Object, required: true } });
// back: voltar à lista; rejected: o servidor recusou o acesso à câmera.
const emit = defineEmits(['back', 'rejected']);

const health = ref(props.device.health);
const online = ref(props.device.online);
const connected = ref(false);
const status = ref('Conectando…');
const frameUrl = ref('');
const events = ref([]);
const eventsLoaded = ref(false);
// Configurações informadas pela câmera (controle remoto); null até ela responder.
const settings = ref(null);
// Evento aberto na janela (vídeo ou foto), inclusive a "Foto agora" recém-tirada.
const photo = ref(null);
const photoBusy = ref(false);
const photoError = ref('');

async function snap() {
  photoBusy.value = true;
  photoError.value = '';
  try {
    photo.value = await takePhoto(props.device.id);
  } catch (err) {
    photoError.value = err.message;
  } finally {
    photoBusy.value = false;
  }
}

// Abas: "live" (imagem e controles) ou "events" (fotos dos movimentos).
const tab = ref('live');
// Movimentos que chegaram enquanto a aba Movimentos estava fechada.
const unseen = ref(0);
// Contador da aba: só os registros de hoje.
const todayCount = computed(() => {
  const today = new Date().toDateString();
  return events.value.filter((event) => new Date(event.at).toDateString() === today).length;
});

const pill = computed(() => {
  if (!connected.value) return { text: 'Conectando', kind: 'warning' };
  if (!online.value) return { text: 'Offline', kind: '' };
  if (frameUrl.value) return { text: 'Ao vivo', kind: 'danger live' };
  return { text: 'Aguardando imagem', kind: 'warning' };
});

let socket = null;
let retryTimer = null;
let closed = false;

function showFrame(data) {
  if (frameUrl.value) URL.revokeObjectURL(frameUrl.value);
  frameUrl.value = URL.createObjectURL(new Blob([data], { type: 'image/jpeg' }));
  status.value = '';
}

function clearFrame() {
  if (frameUrl.value) URL.revokeObjectURL(frameUrl.value);
  frameUrl.value = '';
}

function handleMessage(text) {
  let message;
  try {
    message = JSON.parse(text);
  } catch {
    return;
  }
  if (message.type === 'status') {
    online.value = message.online;
    if (!message.online) {
      status.value = 'Câmera offline. Aguardando conexão…';
      health.value = null;
      settings.value = null;
      clearFrame();
    } else if (!frameUrl.value) {
      status.value = 'Conectado. Aguardando imagem…';
    }
  } else if (message.type === 'health') {
    health.value = message.health;
  } else if (message.type === 'settings') {
    settings.value = message.settings;
  } else if (message.type === 'event') {
    // Evento atualizado (ex.: o vídeo do movimento chegou, ou foi apagado por falta de espaço).
    events.value = events.value.map((item) => (item.id === message.event.id ? message.event : item));
    if (photo.value?.id === message.event.id) photo.value = message.event;
  } else if (message.type === 'eventRemoved') {
    removeEvent(message.id);
  } else if (message.type === 'eventsRemoved') {
    removeEvents(message.ids);
  } else if (message.type === 'motion') {
    events.value.unshift(message.event);
    eventsLoaded.value = true;
    if (tab.value !== 'events') unseen.value += 1;
  }
}

function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${scheme}://${location.host}/ws/view?device=${encodeURIComponent(props.device.id)}`);
  socket.binaryType = 'arraybuffer';
  socket.onopen = () => {
    connected.value = true;
  };
  socket.onmessage = (event) => {
    if (typeof event.data === 'string') handleMessage(event.data);
    else showFrame(event.data);
  };
  socket.onclose = (event) => {
    connected.value = false;
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

function removeEvent(id) {
  events.value = events.value.filter((item) => item.id !== id);
}

function removeEvents(ids) {
  const gone = new Set(ids);
  events.value = events.value.filter((item) => !gone.has(item.id));
  if (photo.value && gone.has(photo.value.id)) photo.value = null;
}

function showTab(next) {
  tab.value = next;
  if (next === 'events') unseen.value = 0;
}

// Controle remoto: pede a mudança à câmera e já mostra o valor novo; a resposta dela confirma.
function configure(changes) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  socket.send(JSON.stringify({ type: 'configure', settings: changes }));
  if (settings.value) settings.value = { ...settings.value, ...changes };
}

// Foco: "lock" foca e mantém; "auto" volta ao automático. A câmera responde com o resultado.
function focus(mode) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  socket.send(JSON.stringify({ type: 'focus', mode }));
  if (mode === 'lock' && settings.value) settings.value = { ...settings.value, focus: 'focusing' };
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
  clearFrame();
});
</script>

<template>
  <PageHeader :title="device.name">
    <template #leading>
      <button type="button" class="btn btn-secondary btn-icon" title="Voltar às câmeras" aria-label="Voltar às câmeras" @click="$emit('back')">
        <AppIcon name="back" />
      </button>
    </template>
    <template #subtitle>
      <span class="pill" :class="pill.kind">{{ pill.text }}</span>
      <HealthInfo :health="health" />
    </template>
  </PageHeader>

  <div class="tabs" role="tablist" aria-label="Seções da câmera">
    <button
      type="button"
      role="tab"
      class="tab"
      :class="{ active: tab === 'live' }"
      :aria-selected="tab === 'live'"
      @click="showTab('live')"
    >
      <AppIcon name="camera" :size="18" />
      Ao vivo
    </button>
    <button
      type="button"
      role="tab"
      class="tab"
      :class="{ active: tab === 'events' }"
      :aria-selected="tab === 'events'"
      @click="showTab('events')"
    >
      <AppIcon name="motion" :size="18" />
      Movimentos
      <span v-if="unseen" class="tab-badge" :aria-label="`${unseen} novos`">{{ unseen }}</span>
      <span v-else-if="todayCount" class="tab-count" :title="`${todayCount} hoje`" :aria-label="`${todayCount} hoje`">{{ todayCount }}</span>
    </button>
  </div>

  <!-- A conexão continua aberta nas duas abas: a imagem volta na hora ao trocar. -->
  <div v-show="tab === 'live'" class="live-view">
    <div class="stage">
      <img v-if="frameUrl" :src="frameUrl" alt="Transmissão ao vivo">
      <div v-else class="stage-status">
        <AppIcon name="camera" :size="36" />
        <span>{{ status }}</span>
      </div>
    </div>
    <CameraControls :settings="settings" :online="online && connected" @change="configure" @focus="focus" />
  </div>

  <div v-show="tab === 'events'">
    <div class="events-toolbar">
      <span class="muted small">Movimentos detectados e fotos tiradas pelo site, numa linha do tempo.</span>
      <button type="button" class="btn btn-secondary" :disabled="!online || photoBusy" @click="snap">
        <AppIcon :name="photoBusy ? 'refresh' : 'photo'" :size="16" />
        {{ photoBusy ? 'Tirando foto…' : 'Foto agora' }}
      </button>
    </div>
    <StatusMessage :text="photoError" kind="error" />
    <MotionEvents
      :device-id="device.id"
      :events="events"
      :loaded="eventsLoaded"
      @open="photo = $event"
      @removed="removeEvents"
    />
  </div>
  <EventDialog v-if="photo" :event="photo" :title="device.name" @close="photo = null" @removed="removeEvent" />
</template>
