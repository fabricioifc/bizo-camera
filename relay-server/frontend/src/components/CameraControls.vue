<script setup>
import AppIcon from './AppIcon.vue';

// Controle remoto: mostra as configurações informadas pela câmera e pede mudanças.
const props = defineProps({
  settings: { type: Object, default: null },
  online: Boolean,
});
const emit = defineEmits(['change']);

const LENS = [
  { value: 'back', label: 'Traseira' },
  { value: 'front', label: 'Frontal' },
];
const LEVELS = [
  { value: 'low', label: 'Baixa' },
  { value: 'medium', label: 'Média' },
  { value: 'high', label: 'Alta' },
];
const ROTATIONS = [0, 90, 180, 270].map((value) => ({ value, label: `${value}°` }));
const ON_OFF = [
  { value: true, label: 'Ligada' },
  { value: false, label: 'Desligada' },
];

function set(key, value) {
  if (props.settings?.[key] !== value) emit('change', { [key]: value });
}
</script>

<template>
  <section class="card" aria-labelledby="controls-title">
    <div class="card-header">
      <div>
        <h2 id="controls-title">Controles da câmera</h2>
        <p class="small muted">As mudanças valem na hora e ficam salvas no celular.</p>
      </div>
    </div>

    <p v-if="!online" class="controls-empty muted">Disponível quando a câmera estiver online.</p>
    <p v-else-if="!settings" class="controls-empty muted">
      Aguardando a câmera. Atualize o app no celular se esta mensagem não sumir.
    </p>

    <div v-else class="controls">
      <div class="control">
        <span class="control-label">Câmera</span>
        <div class="segmented" role="group" aria-label="Câmera">
          <button
            v-for="option in LENS"
            :key="option.value"
            type="button"
            :class="{ active: settings.lens === option.value }"
            :aria-pressed="settings.lens === option.value"
            @click="set('lens', option.value)"
          >{{ option.label }}</button>
        </div>
      </div>

      <div class="control">
        <span class="control-label">Rotação</span>
        <div class="segmented" role="group" aria-label="Rotação">
          <button
            v-for="option in ROTATIONS"
            :key="option.value"
            type="button"
            :class="{ active: settings.rotation === option.value }"
            :aria-pressed="settings.rotation === option.value"
            @click="set('rotation', option.value)"
          >{{ option.label }}</button>
        </div>
      </div>

      <div class="control">
        <span class="control-label">Qualidade</span>
        <div class="segmented" role="group" aria-label="Qualidade">
          <button
            v-for="option in LEVELS"
            :key="option.value"
            type="button"
            :class="{ active: settings.quality === option.value }"
            :aria-pressed="settings.quality === option.value"
            @click="set('quality', option.value)"
          >{{ option.label }}</button>
        </div>
      </div>

      <div class="control">
        <span class="control-label">
          <AppIcon name="sun" :size="14" />
          Lanterna
        </span>
        <div class="segmented" role="group" aria-label="Lanterna">
          <button
            v-for="option in ON_OFF"
            :key="String(option.value)"
            type="button"
            :class="{ active: settings.torch === option.value }"
            :aria-pressed="settings.torch === option.value"
            :disabled="!settings.torchAvailable"
            @click="set('torch', option.value)"
          >{{ option.label }}</button>
        </div>
        <span v-if="!settings.torchAvailable" class="small muted">Sem lanterna nesta câmera.</span>
      </div>

      <div class="control">
        <span class="control-label">
          <AppIcon name="motion" :size="14" />
          Detecção de movimento
        </span>
        <div class="segmented" role="group" aria-label="Detecção de movimento">
          <button
            v-for="option in ON_OFF"
            :key="String(option.value)"
            type="button"
            :class="{ active: settings.motion === option.value }"
            :aria-pressed="settings.motion === option.value"
            @click="set('motion', option.value)"
          >{{ option.label }}</button>
        </div>
      </div>

      <div class="control">
        <span class="control-label">Sensibilidade</span>
        <div class="segmented" role="group" aria-label="Sensibilidade">
          <button
            v-for="option in LEVELS"
            :key="option.value"
            type="button"
            :class="{ active: settings.sensitivity === option.value }"
            :aria-pressed="settings.sensitivity === option.value"
            :disabled="!settings.motion"
            @click="set('sensitivity', option.value)"
          >{{ option.label }}</button>
        </div>
      </div>
    </div>
  </section>
</template>
