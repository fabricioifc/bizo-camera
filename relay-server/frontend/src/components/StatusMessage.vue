<script setup>
defineProps({
  text: { type: String, default: '' },
  error: Boolean,
});
</script>

<template>
  <p v-if="text" class="hint" :class="{ error }">{{ text }}</p>
</template>
