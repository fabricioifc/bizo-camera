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
// A câmera guarda 0, 90, 180 ou 270 graus; 270 aparece como −90 (girar para a esquerda).
const ROTATIONS = [
  { value: 270, label: '−90°' },
  { value: 0, label: '0°' },
  { value: 90, label: '90°' },
  { value: 180, label: '180°' },
];

function set(key, value) {
  if (props.settings?.[key] !== value) emit('change', { [key]: value });
}
</script>

<template>
  <section class="card controls-card" aria-labelledby="controls-title">
    <div class="card-header">
      <div>
        <h2 id="controls-title">Controles</h2>
        <p class="small muted">Valem na hora e ficam salvos no celular.</p>
      </div>
    </div>

    <div v-if="!online" class="controls-empty">
      <AppIcon name="camera" :size="22" />
      <span>Disponíveis quando a câmera estiver online.</span>
    </div>
    <div v-else-if="!settings" class="controls-empty">
      <AppIcon name="refresh" :size="22" />
      <span>Aguardando a câmera. Se não aparecer, atualize o app no celular.</span>
    </div>

    <template v-else>
      <!-- Imagem -->
      <div class="controls-section">
        <h3 class="controls-title">Imagem</h3>

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
          <span class="control-hint">A imagem já acompanha a posição do celular. Use só se ainda sair torta.</span>
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
      </div>

      <!-- Lanterna -->
      <div class="controls-section">
        <div class="switch-row">
          <span class="switch-text">
            <span class="control-label"><AppIcon name="sun" :size="16" /> Lanterna</span>
            <span class="control-hint">
              {{ settings.torchAvailable ? 'Acende a luz do celular.' : 'Esta câmera não tem lanterna.' }}
            </span>
          </span>
          <button
            type="button"
            class="switch"
            role="switch"
            :aria-checked="settings.torch"
            aria-label="Lanterna"
            :disabled="!settings.torchAvailable"
            @click="set('torch', !settings.torch)"
          ></button>
        </div>
      </div>

      <!-- Detecção de movimento -->
      <div class="controls-section">
        <div class="switch-row">
          <span class="switch-text">
            <span class="control-label"><AppIcon name="motion" :size="16" /> Detecção de movimento</span>
            <span class="control-hint">
              {{ settings.motion ? 'Guarda uma foto e avisa a cada movimento.' : 'Nenhum movimento é registrado.' }}
            </span>
          </span>
          <button
            type="button"
            class="switch"
            role="switch"
            :aria-checked="settings.motion"
            aria-label="Detecção de movimento"
            @click="set('motion', !settings.motion)"
          ></button>
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
    </template>
  </section>
</template>
