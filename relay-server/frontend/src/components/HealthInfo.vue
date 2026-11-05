<script setup>
import { computed } from 'vue';
import { healthText, healthWarn } from '../lib/format.js';

const props = defineProps({ health: { type: Object, default: null } });
const text = computed(() => healthText(props.health));
</script>

<template>
  <span v-if="text" class="hint health" :class="{ warn: healthWarn(health) }">{{ text }}</span>
</template>
