<script setup>
import AppIcon from './AppIcon.vue';
import { canInstall, install, installed, isIos } from '../lib/install.js';
</script>

<template>
  <section class="card" aria-labelledby="install-title">
    <div class="card-header">
      <div>
        <h2 id="install-title">Instalar como app</h2>
        <p class="small muted">Abre direto da tela inicial, em tela cheia, como um app do celular ou do computador.</p>
      </div>
    </div>
    <div class="setting">
      <span class="setting-icon"><AppIcon name="download" /></span>
      <div class="setting-text">
        <template v-if="installed">Instalado neste aparelho.</template>
        <template v-else-if="canInstall">Pronto para instalar neste aparelho.</template>
        <template v-else-if="isIos">
          No Safari, toque em <strong>Compartilhar</strong> e depois em <strong>Adicionar à Tela de Início</strong>.
          <p class="small muted">No iPhone, os alertas só funcionam com o site instalado assim.</p>
        </template>
        <template v-else>
          Use o menu do navegador e escolha <strong>Instalar app</strong> (ou <strong>Adicionar à tela inicial</strong>).
        </template>
      </div>
      <button v-if="canInstall && !installed" type="button" class="btn btn-primary" @click="install">
        Instalar
      </button>
    </div>
  </section>
</template>
