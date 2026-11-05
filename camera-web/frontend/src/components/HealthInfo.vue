<script setup>
import { computed } from 'vue';
import AppIcon from './AppIcon.vue';
import { healthChips } from '../lib/format.js';

const props = defineProps({ health: { type: Object, default: null } });
const chips = computed(() => healthChips(props.health));
</script>

<template>
  <div v-if="chips.length" class="chips">
    <span v-for="chip in chips" :key="chip.icon" class="chip" :class="{ warn: chip.warn }">
      <AppIcon :name="chip.icon" :size="14" />
      {{ chip.text }}
    </span>
  </div>
</template>
