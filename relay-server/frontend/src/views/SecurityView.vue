<script setup>
import { onMounted, ref } from 'vue';
import AppIcon from '../components/AppIcon.vue';
import PageHeader from '../components/PageHeader.vue';
import StatusMessage from '../components/StatusMessage.vue';
import { api, ensureOk, post } from '../lib/api.js';

defineProps({ pendingSetup: Boolean });
// account: dados da conta carregados; unauthorized: sessão expirou; done: 2FA ativado.
const emit = defineEmits(['account', 'unauthorized', 'done']);

const account = ref(null);
const loadError = ref('');
const message = ref('');
const messageKind = ref('info');
const busy = ref(false);

const setupQr = ref('');
const setupSecret = ref('');
const enableCode = ref('');
const disableCode = ref('');
const recoveryCodes = ref([]);

function setMessage(text, kind = 'info') {
  message.value = text;
  messageKind.value = kind;
}

async function load() {
  try {
    const response = await api('/api/auth/me');
    if (response.status === 401) {
      emit('unauthorized');
      return;
    }
    account.value = await response.json();
    loadError.value = '';
    emit('account', account.value);
  } catch {
    loadError.value = 'Não foi possível carregar os dados da conta.';
  }
}

/** Roda uma ação do formulário com o botão travado e a mensagem de erro do servidor. */
async function run(action) {
  busy.value = true;
  setMessage('');
  try {
    await action();
  } catch (error) {
    setMessage(error.message, 'error');
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
  setMessage('Verificação em duas etapas desativada.', 'success');
});

onMounted(load);
</script>

<template>
  <PageHeader title="Segurança">
    <template #subtitle>Conta e verificação em duas etapas</template>
  </PageHeader>

  <div class="page-narrow">
    <StatusMessage
      v-if="pendingSetup"
      kind="warning"
      text="Para acessar as câmeras, ative a verificação em duas etapas."
    />
    <StatusMessage :text="loadError" kind="error" />

    <section v-if="account" class="card">
      <div class="card-header"><h2>Conta</h2></div>
      <dl class="rows">
        <div class="row">
          <dt>E-mail</dt>
          <dd>{{ account.email }}</dd>
        </div>
        <div class="row">
          <dt>Login</dt>
          <dd>{{ account.google ? 'Conta Google' : 'Senha' }}</dd>
        </div>
        <div class="row">
          <dt>Verificação em duas etapas</dt>
          <dd>
            <span class="pill" :class="account.twoFactor ? 'success' : 'warning'">
              {{ account.twoFactor ? 'Ativa' : 'Desativada' }}
            </span>
          </dd>
        </div>
      </dl>
    </section>

    <section v-if="account" class="card">
      <div class="card-header">
        <div>
          <h2>Verificação em duas etapas</h2>
          <p class="small muted">Um código do app autenticador é pedido a cada login, além do Google.</p>
        </div>
      </div>

      <div class="card-body stack">
        <!-- 3) Códigos de recuperação, logo depois de ativar -->
        <template v-if="recoveryCodes.length">
          <StatusMessage kind="success" text="Verificação em duas etapas ativada." />
          <div>
            <strong>Guarde estes códigos de recuperação.</strong>
            <p class="muted">Cada um vale uma vez e entra no lugar do código do app, se você perder o celular. Eles não serão mostrados de novo.</p>
            <ul class="codes">
              <li v-for="code in recoveryCodes" :key="code">{{ code }}</li>
            </ul>
            <button type="button" class="btn btn-primary" @click="$emit('done')">
              <AppIcon name="check" :size="16" />
              Já guardei
            </button>
          </div>
        </template>

        <!-- 2) QR code e confirmação -->
        <ol v-else-if="setupQr" class="steps">
          <li>
            <div>
              <strong>Escaneie o QR code</strong>
              <p class="muted">No Google Authenticator, Authy, 1Password ou outro app autenticador.</p>
              <img class="qr" :src="setupQr" alt="QR code da verificação em duas etapas" width="200" height="200">
              <p class="small muted">Ou digite a chave: <code class="secret">{{ setupSecret }}</code></p>
            </div>
          </li>
          <li>
            <form class="form code-form" @submit.prevent="enableTwoFactor">
              <div class="field">
                <label for="enable-code">Digite o código que aparece no app</label>
                <input
                  id="enable-code"
                  v-model="enableCode"
                  class="input input-code"
                  type="text"
                  inputmode="numeric"
                  autocomplete="one-time-code"
                  placeholder="000000"
                  maxlength="6"
                  required
                >
              </div>
              <button type="submit" class="btn btn-primary" :disabled="busy">Confirmar e ativar</button>
            </form>
          </li>
        </ol>

        <!-- Ativa: desativar com um código -->
        <form v-else-if="account.twoFactor" class="form code-form" @submit.prevent="disableTwoFactor">
          <div class="field">
            <label for="disable-code">Para desativar, informe um código do app ou de recuperação</label>
            <input
              id="disable-code"
              v-model="disableCode"
              class="input"
              type="text"
              inputmode="numeric"
              autocomplete="one-time-code"
              required
            >
          </div>
          <button type="submit" class="btn btn-danger" :disabled="busy">Desativar verificação em duas etapas</button>
        </form>

        <!-- 1) Desativada: começar -->
        <div v-else>
          <button type="button" class="btn btn-primary" :disabled="busy" @click="startSetup">
            <AppIcon name="shield-check" :size="18" />
            Ativar verificação em duas etapas
          </button>
        </div>

        <StatusMessage :text="message" :kind="messageKind" />
      </div>
    </section>
  </div>
</template>
