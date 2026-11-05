<script setup>
import AppIcon from './AppIcon.vue';
import ThemeToggle from './ThemeToggle.vue';

defineProps({
  section: { type: String, required: true },
  pendingSetup: Boolean,
  email: { type: String, default: '' },
  connectionOk: Boolean,
});
defineEmits(['navigate', 'logout']);

const ITEMS = [
  { section: 'cameras', icon: 'camera', label: 'Câmeras' },
  { section: 'security', icon: 'shield', label: 'Segurança' },
];
</script>

<template>
  <aside class="sidebar" aria-label="Menu">
    <div class="brand">
      <img src="/icon-192.png" alt="" width="34" height="34">
      <span>Monitor de Câmera</span>
    </div>

    <nav class="nav">
      <span class="nav-label">Menu</span>
      <button
        v-for="item in ITEMS"
        :key="item.section"
        type="button"
        class="nav-item"
        :class="{ active: section === item.section }"
        :aria-current="section === item.section ? 'page' : undefined"
        :disabled="pendingSetup && item.section !== 'security'"
        @click="$emit('navigate', item.section)"
      >
        <AppIcon :name="item.icon" />
        {{ item.label }}
      </button>
    </nav>

    <div class="sidebar-footer">
      <span class="connection">
        <span class="pill" :class="connectionOk ? 'success' : 'danger'">
          {{ connectionOk ? 'Servidor conectado' : 'Sem conexão' }}
        </span>
      </span>
      <ThemeToggle />
      <div class="account">
        <span class="avatar" aria-hidden="true">{{ email ? email[0] : '?' }}</span>
        <span class="account-email" :title="email">{{ email }}</span>
        <button type="button" class="btn btn-ghost btn-icon" title="Sair" aria-label="Sair" @click="$emit('logout')">
          <AppIcon name="logout" />
        </button>
      </div>
    </div>
  </aside>
</template>
