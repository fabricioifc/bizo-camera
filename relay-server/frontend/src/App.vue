<script setup>
import { computed, onMounted, ref } from 'vue';
import AppLayout from './layouts/AppLayout.vue';
import AuthLayout from './layouts/AuthLayout.vue';
import DeviceListView from './views/DeviceListView.vue';
import LoginView from './views/LoginView.vue';
import MfaView from './views/MfaView.vue';
import SecurityView from './views/SecurityView.vue';
import ViewerView from './views/ViewerView.vue';
import { api, MESSAGES } from './lib/api.js';
import { serviceWorker } from './lib/push.js';

// Telas: auth e mfa (sem login, cartão centralizado); list, viewer e security (com a barra lateral).
// Cada tela cuida dos próprios dados; aqui ficam só a navegação e o estado da sessão.
const view = ref('loading');
const connectionOk = ref(false);
const pendingSetup = ref(false);
const email = ref('');
const mfaToken = ref(null);
const openCameraId = ref(null);
const viewerDevice = ref(null);
const authMessage = ref('');
const authError = ref(false);

const section = computed(() => (view.value === 'security' ? 'security' : 'cameras'));

function showAuth(message = '', isError = false) {
  pendingSetup.value = false;
  email.value = '';
  mfaToken.value = null;
  viewerDevice.value = null;
  authMessage.value = message;
  authError.value = isError;
  view.value = 'auth';
}

function showSecurity() {
  view.value = 'security';
}

function showList() {
  viewerDevice.value = null;
  if (pendingSetup.value) {
    showSecurity();
    return;
  }
  view.value = 'list';
}

function navigate(target) {
  if (target === 'security') showSecurity();
  else showList();
}

function openViewer(device) {
  viewerDevice.value = device;
  view.value = 'viewer';
}

function setAccount(account) {
  email.value = account.email;
  pendingSetup.value = account.pending;
}

function requireSetup() {
  pendingSetup.value = true;
  showSecurity();
}

function finishSetup() {
  pendingSetup.value = false;
  showList();
}

/** Carrega a conta da sessão atual. Devolve false se não houver sessão. */
async function loadAccount() {
  const response = await api('/api/auth/me');
  if (!response.ok) return false;
  setAccount(await response.json());
  return true;
}

async function afterMfa() {
  try {
    await loadAccount();
  } catch {
    // A lista mostra o erro de conexão.
  }
  showList();
}

async function logout() {
  try {
    await api('/api/auth/logout', { method: 'POST' });
  } catch {
    // O cookie expira mesmo se o logout estiver sem conexão.
  }
  showAuth();
}

/** Lê e limpa o fragmento da URL: #mfa=, #error= (retorno do Google) ou #camera= (notificação). */
function readFragment() {
  const hash = location.hash.slice(1);
  if (!hash) return null;
  history.replaceState(null, '', location.pathname);
  const [key, value] = hash.split('=');
  if (key === 'mfa' && value) return { mfaToken: decodeURIComponent(value) };
  if (key === 'error') return { error: `error=${value}` };
  if (key === 'camera' && value) return { cameraId: decodeURIComponent(value) };
  return null;
}

async function initialize() {
  const fragment = readFragment();
  if (fragment?.cameraId) openCameraId.value = fragment.cameraId;
  serviceWorker();
  try {
    await fetch('/api/config');
    connectionOk.value = true;
  } catch {
    connectionOk.value = false;
  }

  if (fragment?.mfaToken) {
    mfaToken.value = fragment.mfaToken;
    view.value = 'mfa';
    return;
  }

  try {
    if (await loadAccount()) {
      showList();
      return;
    }
  } catch {
    connectionOk.value = false;
  }

  if (fragment?.error) showAuth(MESSAGES[fragment.error] || MESSAGES['error=google'], true);
  else showAuth();
}

onMounted(initialize);
</script>

<template>
  <AuthLayout v-if="view === 'auth' || view === 'mfa'">
    <LoginView v-if="view === 'auth'" :message="authMessage" :error="authError" />
    <MfaView
      v-else
      :mfa-token="mfaToken"
      @done="afterMfa"
      @expired="showAuth(MESSAGES.mfa_expired, true)"
      @cancel="showAuth()"
    />
  </AuthLayout>

  <AppLayout
    v-else-if="view !== 'loading'"
    :section="section"
    :pending-setup="pendingSetup"
    :email="email"
    :connection-ok="connectionOk"
    @navigate="navigate"
    @logout="logout"
  >
    <SecurityView
      v-if="view === 'security'"
      :pending-setup="pendingSetup"
      @account="setAccount"
      @unauthorized="showAuth()"
      @done="finishSetup"
    />

    <DeviceListView
      v-else-if="view === 'list'"
      v-model:open-id="openCameraId"
      @open="openViewer"
      @connection="connectionOk = $event"
      @unauthorized="showAuth()"
      @setup-required="requireSetup"
    />

    <ViewerView
      v-else-if="view === 'viewer' && viewerDevice"
      :key="viewerDevice.id"
      :device="viewerDevice"
      @back="showList"
      @rejected="showList"
    />
  </AppLayout>
</template>
