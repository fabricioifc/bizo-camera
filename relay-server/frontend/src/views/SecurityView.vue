<script setup>
import { onMounted, ref } from 'vue';
import StatusMessage from '../components/StatusMessage.vue';
import { api, ensureOk, post } from '../lib/api.js';

defineProps({ pendingSetup: Boolean });
// account: dados da conta carregados; unauthorized: sessão expirou; done: 2FA ativado; back: voltar à lista.
const emit = defineEmits(['account', 'unauthorized', 'done', 'back']);

const account = ref(null);
const status = ref('Carregando…');
const message = ref('');
const messageError = ref(false);
const busy = ref(false);

const setupQr = ref('');
const setupSecret = ref('');
const enableCode = ref('');
const disableCode = ref('');
const recoveryCodes = ref([]);

function setMessage(text, isError = false) {
  message.value = text;
  messageError.value = isError;
}

async function load() {
  try {
    const response = await api('/api/auth/me');
    if (response.status === 401) {
      emit('unauthorized');
      return;
    }
    account.value = await response.json();
    emit('account', account.value);
    status.value = `Conta: ${account.value.email}. Verificação em duas etapas ${account.value.twoFactor ? 'ativa' : 'desativada'}.`;
  } catch {
    status.value = 'Não foi possível carregar os dados da conta.';
  }
}

/** Roda uma ação do formulário com o botão travado e a mensagem de erro do servidor. */
async function run(action) {
  busy.value = true;
  setMessage('');
  try {
    await action();
  } catch (error) {
    setMessage(error.message, true);
  } finally {
    busy.value = false;
  }
}

const startSetup = () => run(async () => {
  const data = await (await ensureOk(await post('/api/account/2fa/setup'))).json();
  setupQr.value = data.qr;
  setupSecret.value = data.secret;
});

const enableTwoFactor = () => run(async () => {
  const response = await ensureOk(await post('/api/account/2fa/enable', { code: enableCode.value.trim() }));
  recoveryCodes.value = (await response.json()).recoveryCodes;
  setupQr.value = '';
});

const disableTwoFactor = () => run(async () => {
  await ensureOk(await post('/api/account/2fa/disable', { code: disableCode.value.trim() }));
  disableCode.value = '';
  await load();
  setMessage('Verificação em duas etapas desativada.');
});

onMounted(load);
</script>

<template>
  <section class="panel">
    <h2>Segurança da conta</h2>
    <p v-if="pendingSetup" class="hint">Para usar o monitor, ative a verificação em duas etapas agora.</p>
    <p class="hint">{{ status }}</p>

    <div v-if="account && !account.twoFactor">
      <div v-if="recoveryCodes.length">
        <p class="hint"><strong>Guarde estes códigos de recuperação.</strong> Cada um vale uma vez e não serão mostrados de novo.</p>
        <ul class="codes">
          <li v-for="code in recoveryCodes" :key="code">{{ code }}</li>
        </ul>
        <div class="actions">
          <button type="button" @click="$emit('done')">Já guardei</button>
        </div>
      </div>
      <div v-else-if="setupQr">
        <p class="hint">Escaneie o QR code com seu app autenticador (Google Authenticator, Authy, 1Password…) ou digite a chave manualmente.</p>
        <img :src="setupQr" alt="QR code do 2FA" width="220" height="220">
        <p class="hint">Chave: <code>{{ setupSecret }}</code></p>
        <form @submit.prevent="enableTwoFactor">
          <input v-model="enableCode" type="text" inputmode="numeric" autocomplete="one-time-code" placeholder="000000" maxlength="6" required>
          <div class="actions">
            <button type="submit" :disabled="busy">Confirmar e ativar</button>
          </div>
        </form>
      </div>
      <button v-else type="button" :disabled="busy" @click="startSetup">
        Ativar verificação em duas etapas
      </button>
    </div>

    <form v-else-if="account" @submit.prevent="disableTwoFactor">
      <input v-model="disableCode" type="text" inputmode="numeric" autocomplete="one-time-code" placeholder="Código do app ou de recuperação" required>
      <div class="actions">
        <button type="submit" class="danger" :disabled="busy">Desativar verificação em duas etapas</button>
      </div>
    </form>

    <StatusMessage :text="message" :error="messageError" />
    <div v-if="!pendingSetup" class="actions">
      <button type="button" class="secondary" @click="$emit('back')">← Voltar</button>
    </div>
  </section>
</template>
