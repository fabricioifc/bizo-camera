<script setup>
import AppIcon from './AppIcon.vue';
import { theme } from '../lib/theme.js';

const OPTIONS = [
  { value: 'light', icon: 'sun', label: 'Claro' },
  { value: 'dark', icon: 'moon', label: 'Escuro' },
  { value: 'system', icon: 'system', label: 'Automático' },
];
</script>

<template>
  <div class="segmented" role="group" aria-label="Tema">
    <button
      v-for="option in OPTIONS"
      :key="option.value"
      type="button"
      :class="{ active: theme === option.value }"
      :aria-pressed="theme === option.value"
      :title="option.label"
      :aria-label="option.label"
      @click="theme = option.value"
    >
      <AppIcon :name="option.icon" :size="16" />
      <span class="segmented-label">{{ option.label }}</span>
    </button>
  </div>
</template>
