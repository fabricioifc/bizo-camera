<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import AppIcon from '../components/AppIcon.vue';
import AppSidebar from '../components/AppSidebar.vue';

defineProps({
  section: { type: String, required: true },
  pendingSetup: Boolean,
  email: { type: String, default: '' },
  connectionOk: Boolean,
});
const emit = defineEmits(['navigate', 'logout']);

// No celular, a barra lateral abre como gaveta.
const drawerOpen = ref(false);

function navigate(target) {
  drawerOpen.value = false;
  emit('navigate', target);
}

function onKey(event) {
  if (event.key === 'Escape') drawerOpen.value = false;
}

onMounted(() => window.addEventListener('keydown', onKey));
onBeforeUnmount(() => window.removeEventListener('keydown', onKey));
</script>

<template>
  <div class="shell" :class="{ 'drawer-open': drawerOpen }">
    <AppSidebar
      :section="section"
      :pending-setup="pendingSetup"
      :email="email"
      :connection-ok="connectionOk"
      @navigate="navigate"
      @logout="$emit('logout')"
    />
    <div class="backdrop" @click="drawerOpen = false"></div>

    <div>
      <header class="topbar">
        <button type="button" class="btn btn-ghost btn-icon" aria-label="Abrir menu" @click="drawerOpen = true">
          <AppIcon name="menu" />
        </button>
        <span class="topbar-title brand">
          <img src="/icon-192.png" alt="" width="28" height="28">
          Monitor de Câmera
        </span>
      </header>
      <main class="content">
        <slot />
      </main>
    </div>
  </div>
</template>
