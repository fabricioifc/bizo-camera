<script setup>
import AppIcon from './AppIcon.vue';
import { formatWhen, snapshotUrl } from '../lib/format.js';

defineProps({
  events: { type: Array, required: true },
  loaded: Boolean,
});
</script>

<template>
  <section aria-label="Últimos movimentos">
    <div v-if="loaded && !events.length" class="card empty">
      <span class="empty-icon"><AppIcon name="motion" :size="28" /></span>
      <h2>Nenhum movimento registrado</h2>
      <p class="muted">Quando a câmera detectar movimento, a foto aparece aqui. Use "Foto agora" para conferir a câmera na hora.</p>
    </div>
    <ul v-else class="events">
      <li v-for="event in events" :key="event.id" class="card event">
        <a v-if="event.snapshot" :href="snapshotUrl(event.id)" target="_blank" rel="noopener" title="Abrir foto">
          <img :src="snapshotUrl(event.id)" alt="Foto do movimento" loading="lazy">
        </a>
        <span v-else class="no-photo"><AppIcon name="motion" /></span>
        <span class="event-time">
          <span class="event-kind" :class="event.kind">{{ event.kind === 'photo' ? 'Foto' : 'Movimento' }}</span>
          {{ formatWhen(event.at) }}
        </span>
      </li>
    </ul>
  </section>
</template>
