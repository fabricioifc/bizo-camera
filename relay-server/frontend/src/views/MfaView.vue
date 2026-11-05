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
  message.value = '';
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
  <h2>Código de verificação</h2>
  <p class="muted">Digite os 6 números do seu app autenticador ou um código de recuperação.</p>
  <form class="form" @submit.prevent="submit">
    <div class="field">
      <label for="mfa-code" class="sr-only">Código</label>
      <input
        id="mfa-code"
        v-model="code"
        class="input input-code"
        type="text"
        inputmode="numeric"
        autocomplete="one-time-code"
        placeholder="000000"
        maxlength="9"
        autofocus
        required
      >
    </div>
    <button type="submit" class="btn btn-primary btn-block" :disabled="busy">Confirmar</button>
    <button type="button" class="btn btn-ghost btn-block" :disabled="busy" @click="$emit('cancel')">Voltar ao login</button>
  </form>
  <StatusMessage :text="message" kind="error" />
</template>
