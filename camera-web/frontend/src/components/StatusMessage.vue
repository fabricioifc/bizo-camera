<script setup>
import { computed } from 'vue';
import AppIcon from './AppIcon.vue';

const props = defineProps({
  text: { type: String, default: '' },
  // info, error, warning ou success
  kind: { type: String, default: 'info' },
});
const icon = computed(() => ({ error: 'alert', warning: 'alert', success: 'check' })[props.kind] || 'bell');
</script>

<template>
  <div v-if="text" class="notice" :class="kind" :role="kind === 'error' ? 'alert' : 'status'">
    <AppIcon :name="icon" :size="18" />
    <span>{{ text }}</span>
  </div>
</template>
