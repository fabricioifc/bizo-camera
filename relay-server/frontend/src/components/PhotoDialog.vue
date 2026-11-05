<script setup>
import { onBeforeUnmount, onMounted } from 'vue';
import AppIcon from './AppIcon.vue';
import { formatWhen, snapshotUrl } from '../lib/format.js';

// Janela com a "Foto agora" recém-tirada. Fecha no X, no fundo ou com Esc.
const props = defineProps({
  event: { type: Object, required: true },
  title: { type: String, default: '' },
});
const emit = defineEmits(['close']);

function onKey(event) {
  if (event.key === 'Escape') emit('close');
}

onMounted(() => window.addEventListener('keydown', onKey));
onBeforeUnmount(() => window.removeEventListener('keydown', onKey));
</script>

<template>
  <div class="dialog-backdrop" @click.self="$emit('close')">
    <div class="dialog card" role="dialog" aria-modal="true" :aria-label="`Foto de ${title}`">
      <div class="card-header">
        <div>
          <h2>{{ title }}</h2>
          <p class="small muted">Foto {{ formatWhen(props.event.at) }} · guardada em Movimentos</p>
        </div>
        <button type="button" class="btn btn-ghost btn-icon" aria-label="Fechar" title="Fechar" @click="$emit('close')">
          <AppIcon name="close" />
        </button>
      </div>
      <a class="dialog-photo" :href="snapshotUrl(props.event.id)" target="_blank" rel="noopener" title="Abrir em tamanho real">
        <img :src="snapshotUrl(props.event.id)" alt="Foto da câmera">
      </a>
    </div>
  </div>
</template>
