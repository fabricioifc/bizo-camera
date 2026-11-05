<script setup>
import StatusMessage from '../components/StatusMessage.vue';

defineProps({
  message: { type: String, default: '' },
  error: Boolean,
});
</script>

<template>
  <h2>Entrar</h2>
  <p class="muted">Use sua conta Google. Depois, confirme com o código de verificação em duas etapas.</p>
  <a class="btn btn-google btn-block" href="/api/auth/google/start">
    <span class="g" aria-hidden="true">G</span>
    Entrar com Google
  </a>
  <StatusMessage :text="message" :kind="error ? 'error' : 'info'" />
</template>
