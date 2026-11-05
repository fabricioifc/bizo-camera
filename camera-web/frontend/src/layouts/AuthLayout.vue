<script setup>
import ThemeToggle from '../components/ThemeToggle.vue';
</script>

<template>
  <div class="auth">
    <div class="auth-brand">
      <img src="/icon-192.png" alt="" width="64" height="64">
      <h1>Monitor de Câmera</h1>
    </div>
    <main class="card auth-card">
      <slot />
    </main>
    <ThemeToggle />
  </div>
</template>
