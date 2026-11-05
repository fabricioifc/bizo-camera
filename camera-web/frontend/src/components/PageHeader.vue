<script setup>
defineProps({ title: { type: String, required: true } });
</script>

<!-- Cabeçalho de tela: [leading] título + subtítulo, e ações à direita (abaixo, no celular). -->
<template>
  <div class="page-header">
    <div class="page-header-main">
      <slot name="leading" />
      <div class="page-heading">
        <h1>{{ title }}</h1>
        <div v-if="$slots.subtitle" class="page-subtitle">
          <slot name="subtitle" />
        </div>
      </div>
    </div>
    <div v-if="$slots.actions" class="actions">
      <slot name="actions" />
    </div>
  </div>
</template>
