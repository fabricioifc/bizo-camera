<script setup>
import AppIcon from './AppIcon.vue';
import { formatWhen, snapshotUrl } from '../lib/format.js';

defineProps({
  events: { type: Array, required: true },
  loaded: Boolean,
});
defineEmits(['open']);
</script>

<template>
  <section aria-label="Últimos movimentos">
    <div v-if="loaded && !events.length" class="card empty">
      <span class="empty-icon"><AppIcon name="motion" :size="28" /></span>
      <h2>Nenhum movimento registrado</h2>
      <p class="muted">Quando a câmera detectar movimento, a foto e o vídeo aparecem aqui. Use "Foto agora" para conferir a câmera na hora.</p>
    </div>
    <ul v-else class="events">
      <li v-for="event in events" :key="event.id" class="card event">
        <button type="button" class="event-open" :title="event.clip ? 'Ver vídeo' : 'Ver foto'" @click="$emit('open', event)">
          <img v-if="event.snapshot" :src="snapshotUrl(event.id)" alt="" loading="lazy">
          <span v-else class="no-photo"><AppIcon name="motion" /></span>
          <span v-if="event.clip" class="event-play" aria-hidden="true"><AppIcon name="play" :size="20" /></span>
        </button>
        <span class="event-time">
          <span class="event-kind" :class="event.kind">{{ event.kind === 'photo' ? 'Foto' : event.clip ? 'Vídeo' : 'Movimento' }}</span>
          {{ formatWhen(event.at) }}
          <span v-if="event.clipPending" class="muted small">· gravando vídeo…</span>
        </span>
      </li>
    </ul>
  </section>
</template>
