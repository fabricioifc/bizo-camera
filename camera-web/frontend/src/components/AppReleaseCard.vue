<script setup>
import { onMounted, ref } from 'vue';
import AppIcon from './AppIcon.vue';
import StatusMessage from './StatusMessage.vue';
import { api, errorText } from '../lib/api.js';

// Atualização do app da câmera: envia o APK de release; os celulares baixam e instalam.
const latest = ref(null);
const loaded = ref(false);
const busy = ref(false);
const message = ref('');
const messageKind = ref('info');
const fileInput = ref(null);

function formatSize(bytes) {
  return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} MB`;
}

function formatDate(at) {
  return new Date(at).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

async function load() {
  try {
    const response = await api('/api/app/latest');
    latest.value = response.ok ? await response.json() : null;
  } catch {
    latest.value = null;
  } finally {
    loaded.value = true;
  }
}

async function upload(event) {
  const file = event.target.files?.[0];
  event.target.value = '';
  if (!file) return;
  busy.value = true;
  message.value = '';
  try {
    const response = await fetch('/api/app/release', {
      method: 'POST',
      headers: { 'Content-Type': 'application/vnd.android.package-archive' },
      body: file,
    });
    if (!response.ok) {
      message.value = await errorText(response);
      messageKind.value = 'error';
      return;
    }
    latest.value = await response.json();
    message.value = `Versão ${latest.value.versionName} publicada. As câmeras avisam e instalam com um toque.`;
    messageKind.value = 'success';
  } catch {
    message.value = 'Não foi possível enviar o arquivo.';
    messageKind.value = 'error';
  } finally {
    busy.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="card" aria-labelledby="release-title">
    <div class="card-header">
      <div>
        <h2 id="release-title">Atualização do app da câmera</h2>
        <p class="small muted">Envie o APK de release; os celulares com o app avisam e instalam a versão nova.</p>
      </div>
    </div>
    <div class="setting">
      <span class="setting-icon"><AppIcon name="refresh" /></span>
      <div class="setting-text">
        <template v-if="!loaded">Verificando…</template>
        <template v-else-if="latest">
          Versão publicada: <strong>{{ latest.versionName }}</strong> ({{ latest.versionCode }})
          <p class="small muted">{{ formatSize(latest.size) }} · enviada em {{ formatDate(latest.publishedAt) }}</p>
        </template>
        <template v-else>Nenhuma versão publicada.</template>
      </div>
      <input ref="fileInput" type="file" accept=".apk,application/vnd.android.package-archive" hidden @change="upload">
      <button type="button" class="btn btn-primary" :disabled="busy" @click="fileInput.click()">
        {{ busy ? 'Enviando…' : 'Enviar APK' }}
      </button>
    </div>
    <div v-if="message" class="card-body release-message">
      <StatusMessage :text="message" :kind="messageKind" />
    </div>
  </section>
</template>
