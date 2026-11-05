<script setup>
import { ref } from 'vue';
import StatusMessage from '../components/StatusMessage.vue';
import { errorCode, errorText, post } from '../lib/api.js';

const props = defineProps({ mfaToken: { type: String, required: true } });
const emit = defineEmits(['done', 'expired', 'cancel']);

const code = ref('');
const message = ref('');
const busy = ref(false);

async function submit() {
  if (!code.value.trim()) return;
  busy.value = true;
  try {
    const response = await post('/api/auth/mfa', { mfaToken: props.mfaToken, code: code.value.trim() });
    if (response.ok) {
      emit('done');
    } else if ((await errorCode(response)) === 'mfa_expired') {
      emit('expired');
    } else {
      message.value = await errorText(response);
    }
  } catch {
    message.value = 'Sem conexão com o servidor.';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="panel">
    <h2>Código de verificação</h2>
    <p class="hint">Digite o código de 6 números do seu app autenticador, ou um código de recuperação.</p>
    <form @submit.prevent="submit">
      <input v-model="code" type="text" inputmode="numeric" autocomplete="one-time-code" placeholder="000000" maxlength="9" required>
      <div class="actions">
        <button type="submit" :disabled="busy">Confirmar</button>
        <button type="button" class="secondary" :disabled="busy" @click="$emit('cancel')">Cancelar</button>
      </div>
    </form>
    <StatusMessage :text="message" error />
  </section>
</template>
