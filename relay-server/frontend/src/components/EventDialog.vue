<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import AppIcon from './AppIcon.vue';
import StatusMessage from './StatusMessage.vue';
import { api, ensureOk } from '../lib/api.js';
import { clipUrl, formatWhen, snapshotUrl } from '../lib/format.js';

// Janela de um evento: vídeo do movimento (se houver) ou foto, com baixar e remover.
// Fecha no X, no fundo ou com Esc.
const props = defineProps({
  event: { type: Object, required: true },
  title: { type: String, default: '' },
});
const emit = defineEmits(['close', 'removed']);

const removing = ref(false);
const error = ref('');

const label = computed(() => {
  if (props.event.kind === 'photo') return 'Foto';
  return props.event.clip ? 'Vídeo do movimento' : 'Movimento';
});
const downloadUrl = computed(() => `${props.event.clip ? clipUrl(props.event.id) : snapshotUrl(props.event.id)}?download=1`);

async function remove() {
  if (!window.confirm(`Remover este ${props.event.clip ? 'vídeo' : 'registro'}? Não dá para desfazer.`)) return;
  removing.value = true;
  error.value = '';
  try {
    await ensureOk(await api(`/api/events/${encodeURIComponent(props.event.id)}`, { method: 'DELETE' }));
    emit('removed', props.event.id);
    emit('close');
  } catch (err) {
    error.value = err.message;
  } finally {
    removing.value = false;
  }
}

function onKey(event) {
  if (event.key === 'Escape') emit('close');
}

onMounted(() => window.addEventListener('keydown', onKey));
onBeforeUnmount(() => window.removeEventListener('keydown', onKey));
</script>

<template>
  <div class="dialog-backdrop" @click.self="$emit('close')">
    <div class="dialog card" role="dialog" aria-modal="true" :aria-label="`${label} de ${title}`">
      <div class="card-header">
        <div>
          <h2>{{ title }}</h2>
          <p class="small muted">{{ label }} · {{ formatWhen(props.event.at) }}</p>
        </div>
        <button type="button" class="btn btn-ghost btn-icon" aria-label="Fechar" title="Fechar" @click="$emit('close')">
          <AppIcon name="close" />
        </button>
      </div>

      <div class="dialog-media">
        <video
          v-if="props.event.clip"
          :src="clipUrl(props.event.id)"
          :poster="props.event.snapshot ? snapshotUrl(props.event.id) : undefined"
          controls
          autoplay
          muted
          playsinline
        ></video>
        <img v-else-if="props.event.snapshot" :src="snapshotUrl(props.event.id)" :alt="label">
        <p v-else class="dialog-empty">Sem imagem neste registro.</p>
      </div>

      <div class="card-body dialog-actions">
        <StatusMessage :text="error" kind="error" />
        <p v-if="props.event.clipPending" class="small muted">O vídeo deste movimento ainda está chegando.</p>
        <div class="actions">
          <a
            v-if="props.event.clip || props.event.snapshot"
            class="btn btn-secondary"
            :href="downloadUrl"
            download
          >
            <AppIcon name="download" :size="16" />
            Baixar {{ props.event.clip ? 'vídeo' : 'foto' }}
          </a>
          <button type="button" class="btn btn-danger" :disabled="removing" @click="remove">
            <AppIcon name="trash" :size="16" />
            Remover
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
