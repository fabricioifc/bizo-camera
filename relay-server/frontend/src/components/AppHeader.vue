<script setup>
defineProps({
  connectionOk: Boolean,
  showSecurity: Boolean,
  showLogout: Boolean,
});
defineEmits(['security', 'logout']);
</script>

<template>
  <header>
    <h1>Monitor de Câmera</h1>
    <div class="header-actions">
      <span class="badge" :class="connectionOk ? 'ok' : 'err'">
        {{ connectionOk ? 'Servidor conectado' : 'Sem conexão' }}
      </span>
      <button v-if="showSecurity" type="button" class="secondary" @click="$emit('security')">Segurança</button>
      <button v-if="showLogout" type="button" class="secondary" @click="$emit('logout')">Sair</button>
    </div>
  </header>
</template>
