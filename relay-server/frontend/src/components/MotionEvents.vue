<script setup>
import { formatWhen, snapshotUrl } from '../lib/format.js';

defineProps({
  events: { type: Array, required: true },
  loaded: Boolean,
});
</script>

<template>
  <h2 class="events-title">Últimos movimentos</h2>
  <p v-if="loaded && !events.length" class="hint">Nenhum movimento registrado.</p>
  <ul class="events">
    <li v-for="event in events" :key="event.id" class="event">
      <a v-if="event.snapshot" :href="snapshotUrl(event.id)" target="_blank" rel="noopener">
        <img :src="snapshotUrl(event.id)" alt="Foto do movimento" loading="lazy">
      </a>
      <span class="hint">{{ formatWhen(event.at) }}</span>
    </li>
  </ul>
</template>
