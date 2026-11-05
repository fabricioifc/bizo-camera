<script setup>
import { nextTick, ref } from 'vue';
import AppIcon from './AppIcon.vue';
import HealthInfo from './HealthInfo.vue';
import PhotoDialog from './PhotoDialog.vue';
import { api, ensureOk, takePhoto } from '../lib/api.js';
import { formatWhen } from '../lib/format.js';

const props = defineProps({ device: { type: Object, required: true } });
// open: assistir; changed: nome alterado ou câmera removida (recarregar a lista).
const emit = defineEmits(['open', 'changed']);

const editing = ref(false);
const name = ref('');
const nameInput = ref(null);
const busy = ref(false);
const error = ref('');
// "Foto agora": foto em qualidade alta, mostrada numa janela e guardada em Movimentos.
const photo = ref(null);
const photoBusy = ref(false);

function deviceUrl() {
  return `/api/devices/${encodeURIComponent(props.device.id)}`;
}

async function beginRename() {
  name.value = props.device.name;
  error.value = '';
  editing.value = true;
  await nextTick();
  nameInput.value?.select();
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

async function snap() {
  photoBusy.value = true;
  error.value = '';
  try {
    photo.value = await takePhoto(props.device.id);
  } catch (err) {
    error.value = err.message;
  } finally {
    photoBusy.value = false;
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
  <article class="card device">
    <div class="device-top">
      <span class="device-icon" :class="{ online: device.online }"><AppIcon name="camera" /></span>
      <div class="device-title">
        <form v-if="editing" class="rename" @submit.prevent="saveName" @keydown.esc="editing = false">
          <input ref="nameInput" v-model="name" class="input" type="text" maxlength="40" aria-label="Nome da câmera" required>
          <button type="submit" class="btn btn-primary btn-icon" :disabled="busy" title="Salvar" aria-label="Salvar">
            <AppIcon name="check" />
          </button>
          <button type="button" class="btn btn-ghost btn-icon" title="Cancelar" aria-label="Cancelar" @click="editing = false">
            <AppIcon name="close" />
          </button>
        </form>
        <template v-else>
          <strong :title="device.name">{{ device.name }}</strong>
          <span class="pill" :class="device.online ? 'success' : ''">{{ device.online ? 'Online' : 'Offline' }}</span>
        </template>
      </div>
      <div v-if="!editing" class="device-tools">
        <button type="button" class="btn btn-ghost btn-icon" title="Renomear" aria-label="Renomear" @click="beginRename">
          <AppIcon name="edit" :size="18" />
        </button>
        <button type="button" class="btn btn-ghost btn-icon" title="Remover" aria-label="Remover" :disabled="busy" @click="remove">
          <AppIcon name="trash" :size="18" />
        </button>
      </div>
    </div>

    <div class="device-body">
      <HealthInfo :health="device.health" />
      <span class="device-meta muted">
        <AppIcon name="motion" :size="16" />
        {{ device.lastMotionAt ? `Último movimento ${formatWhen(device.lastMotionAt)}` : 'Nenhum movimento registrado' }}
      </span>
      <!-- Versão do app no celular; aviso quando há versão publicada mais nova -->
      <span v-if="device.app" class="device-meta muted">
        <AppIcon name="refresh" :size="16" />
        App {{ device.app.versionName }}
        <span
          v-if="device.app.update"
          class="pill warning plain"
          :title="`Versão ${device.app.update.versionName} publicada. O celular avisa e instala com um toque; abra o app ou aguarde a notificação.`"
        >Atualização pendente: {{ device.app.update.versionName }}</span>
      </span>
      <span v-if="!device.online && device.lastSeenAt" class="device-meta muted">
        <AppIcon name="alert" :size="16" />
        Offline desde {{ formatWhen(device.lastSeenAt) }}
      </span>
      <p v-if="error" class="small text-danger" role="alert">{{ error }}</p>
    </div>

    <div class="device-footer">
      <button type="button" class="btn btn-primary" :disabled="!device.online" @click="$emit('open', device)">
        <AppIcon name="play" :size="16" />
        {{ device.online ? 'Assistir' : 'Câmera offline' }}
      </button>
      <button
        type="button"
        class="btn btn-secondary btn-icon"
        :class="{ spinning: photoBusy }"
        :disabled="!device.online || photoBusy"
        :title="photoBusy ? 'Tirando foto…' : 'Foto agora'"
        :aria-label="photoBusy ? 'Tirando foto' : 'Foto agora'"
        @click="snap"
      >
        <AppIcon :name="photoBusy ? 'refresh' : 'photo'" :size="18" />
      </button>
    </div>
    <PhotoDialog v-if="photo" :event="photo" :title="device.name" @close="photo = null" />
  </article>
</template>
