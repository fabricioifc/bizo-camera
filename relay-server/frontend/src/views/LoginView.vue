<script setup>
import StatusMessage from '../components/StatusMessage.vue';

defineProps({
  message: { type: String, default: '' },
  error: Boolean,
});
</script>

<template>
  <section class="panel">
    <h2>Entrar</h2>
    <p class="hint">Entre com sua conta Google. Depois, confirme o código de verificação em duas etapas.</p>
    <a class="button google" href="/api/auth/google/start">Entrar com Google</a>
    <StatusMessage :text="message" :error="error" />
  </section>
</template>
