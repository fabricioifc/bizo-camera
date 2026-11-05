<script setup>
import AppIcon from './AppIcon.vue';
import { formatWhen, snapshotUrl } from '../lib/format.js';

defineProps({
  events: { type: Array, required: true },
  loaded: Boolean,
});
</script>

<template>
  <section class="card events-panel" aria-label="Últimos movimentos">
    <div class="card-header">
      <h2>Últimos movimentos</h2>
      <span v-if="events.length" class="pill plain">{{ events.length }}</span>
    </div>
    <p v-if="loaded && !events.length" class="events-empty muted">Nenhum movimento registrado.</p>
    <ul v-else class="events">
      <li v-for="event in events" :key="event.id" class="event">
        <a v-if="event.snapshot" :href="snapshotUrl(event.id)" target="_blank" rel="noopener" title="Abrir foto">
          <img :src="snapshotUrl(event.id)" alt="Foto do movimento" loading="lazy">
        </a>
        <span v-else class="no-photo"><AppIcon name="motion" /></span>
        <span>{{ formatWhen(event.at) }}</span>
      </li>
    </ul>
  </section>
</template>
