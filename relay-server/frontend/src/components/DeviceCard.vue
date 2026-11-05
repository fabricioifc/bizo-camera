<script setup>
import { ref } from 'vue';
import HealthInfo from './HealthInfo.vue';
import { api, ensureOk } from '../lib/api.js';
import { formatWhen } from '../lib/format.js';

const props = defineProps({ device: { type: Object, required: true } });
// open: assistir; changed: nome alterado ou câmera removida (recarregar a lista).
const emit = defineEmits(['open', 'changed']);

const editing = ref(false);
const name = ref('');
const busy = ref(false);
const error = ref('');

function deviceUrl() {
  return `/api/devices/${encodeURIComponent(props.device.id)}`;
}

function beginRename() {
  name.value = props.device.name;
  error.value = '';
  editing.value = true;
}

async function run(request) {
  busy.value = true;
  error.value = '';
  try {
    await ensureOk(await request());
    editing.value = false;
    emit('changed');
  } catch (err) {
    error.value = err.message;
  } finally {
    busy.value = false;
  }
}

function saveName() {
  return run(() => api(deviceUrl(), { method: 'PATCH', body: JSON.stringify({ name: name.value }) }));
}

function remove() {
  if (!window.confirm(`Remover "${props.device.name}" e revogar o acesso deste aparelho?`)) return;
  return run(() => api(deviceUrl(), { method: 'DELETE' }));
}
</script>

<template>
  <article class="card">
    <div class="card-top">
      <span class="dot" :class="device.online ? 'on' : 'off'"></span>
      <strong>{{ device.name }}</strong>
    </div>
    <span class="hint">{{ device.online ? 'Online' : 'Offline' }}</span>
    <HealthInfo :health="device.health" />
    <span v-if="device.lastMotionAt" class="hint">Último movimento: {{ formatWhen(device.lastMotionAt) }}</span>
    <button :disabled="!device.online" @click="$emit('open', device)">Assistir</button>
    <div class="actions device-actions">
      <button type="button" class="secondary" @click="beginRename">Renomear</button>
      <button type="button" class="danger" :disabled="busy" @click="remove">Remover</button>
    </div>
    <form v-if="editing" class="device-rename-form" @submit.prevent="saveName">
      <input v-model="name" type="text" maxlength="40" aria-label="Nome da câmera" required>
      <button type="submit" :disabled="busy">Salvar</button>
      <button type="button" class="secondary" @click="editing = false">Cancelar</button>
    </form>
    <span v-if="error" class="hint device-message error">{{ error }}</span>
  </article>
</template>
