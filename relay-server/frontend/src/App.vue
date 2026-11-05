<script setup>
import { computed, onMounted, ref } from 'vue';
import AppHeader from './components/AppHeader.vue';
import DeviceListView from './views/DeviceListView.vue';
import LoginView from './views/LoginView.vue';
import MfaView from './views/MfaView.vue';
import SecurityView from './views/SecurityView.vue';
import ViewerView from './views/ViewerView.vue';
import { api, MESSAGES } from './lib/api.js';
import { serviceWorker } from './lib/push.js';

// Telas: auth, mfa, security, list, viewer. Cada tela cuida dos próprios dados;
// aqui ficam só a navegação e o estado da sessão.
const view = ref('auth');
const connectionOk = ref(false);
const signedIn = ref(false);
const pendingSetup = ref(false);
const mfaToken = ref(null);
const openCameraId = ref(null);
const viewerDevice = ref(null);
const authMessage = ref('');
const authError = ref(false);

const showLogout = computed(() => signedIn.value && !['auth', 'mfa'].includes(view.value));
const showSecurityButton = computed(() => signedIn.value && !pendingSetup.value && view.value === 'list');

function showAuth(message = '', isError = false) {
  signedIn.value = false;
  pendingSetup.value = false;
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
  signedIn.value = true;
  view.value = 'list';
}

function openViewer(device) {
  viewerDevice.value = device;
  view.value = 'viewer';
}

function setAccount(account) {
  signedIn.value = true;
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
    const response = await api('/api/auth/me');
    if (response.ok) {
      setAccount(await response.json());
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
  <AppHeader
    :connection-ok="connectionOk"
    :show-security="showSecurityButton"
    :show-logout="showLogout"
    @security="showSecurity"
    @logout="logout"
  />

  <main>
    <LoginView v-if="view === 'auth'" :message="authMessage" :error="authError" />

    <MfaView
      v-else-if="view === 'mfa'"
      :mfa-token="mfaToken"
      @done="showList"
      @expired="showAuth(MESSAGES.mfa_expired, true)"
      @cancel="showAuth()"
    />

    <SecurityView
      v-else-if="view === 'security'"
      :pending-setup="pendingSetup"
      @account="setAccount"
      @unauthorized="showAuth()"
      @done="finishSetup"
      @back="showList"
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
  </main>
</template>
