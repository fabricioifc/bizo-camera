<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

const RECONNECT_MS = 3000;
const LIST_REFRESH_MS = 30_000;
const LOW_BATTERY = 20;

const view = ref('auth');
const connectionOk = ref(false);
const signedIn = ref(false);
const pendingSetup = ref(false);
const pendingMfaToken = ref(null);
const openCameraId = ref(null);
const authMessage = ref('');
const authError = ref(false);
const mfaCode = ref('');
const mfaMessage = ref('');
const mfaError = ref(false);
const mfaBusy = ref(false);
const securityStatus = ref('');
const securityMessage = ref('');
const securityError = ref(false);
const account = ref(null);
const setupDetails = ref(false);
const setupQr = ref('');
const setupSecret = ref('');
const enableCode = ref('');
const disableCode = ref('');
const recoveryCodes = ref([]);
const recoveryVisible = ref(false);
const securityBusy = ref(false);
const devices = ref([]);
const listError = ref('');
const editingDeviceId = ref(null);
const editName = ref('');
const deviceBusyId = ref(null);
const deviceErrors = ref({});
const alertsVisible = ref(false);
const alertsText = ref('');
const alertsButtonText = ref('');
const alertsButtonHidden = ref(false);
const alertsBusy = ref(false);
const viewerDevice = ref(null);
const viewerHealth = ref(null);
const viewerStatus = ref('Conectando…');
const frameUrl = ref('');
const events = ref([]);
const eventsEmpty = ref(false);

const logoutVisible = computed(() => signedIn.value && !['auth', 'mfa'].includes(view.value));
const securityVisible = computed(() => signedIn.value && !pendingSetup.value && view.value === 'list');
const pushSupported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;

let swRegistration = null;
let listTimer = null;
let viewerSocket = null;
let viewerRetryTimer = null;
let lastFrameUrl = null;
let viewerClosing = false;

const MESSAGES = {
  account_locked: 'Conta bloqueada por 15 minutos após muitas senhas erradas.',
  invalid_code: 'Código incorreto. Confira o app autenticador e tente de novo.',
  invalid_name: 'Digite um nome de até 40 caracteres.',
  not_found: 'Esta câmera não existe mais ou não pertence à sua conta.',
  mfa_expired: 'A verificação expirou. Entre com o Google de novo.',
  too_many_requests: 'Muitas tentativas. Aguarde um minuto.',
  already_enabled: 'A verificação em duas etapas já está ativa.',
  setup_required: 'Comece a configuração antes de confirmar o código.',
  not_enabled: 'A verificação em duas etapas não está ativa.',
  invalid_credentials: 'Senha incorreta.',
  'error=google': 'Não foi possível entrar com o Google. Tente de novo.',
  'error=signup_disabled': 'O cadastro está fechado. Peça acesso ao dono do servidor.',
};

function showView(next) {
  view.value = next;
}

function setAuthMessage(text, isError = false) {
  authMessage.value = text;
  authError.value = isError;
}

function setMfaMessage(text, isError = false) {
  mfaMessage.value = text;
  mfaError.value = isError;
}

function setSecurityMessage(text, isError = false) {
  securityMessage.value = text;
  securityError.value = isError;
}

async function errorText(response) {
  const data = await response.json().catch(() => ({}));
  return MESSAGES[data.error] || 'Não foi possível concluir a ação.';
}

function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body) headers['Content-Type'] = 'application/json';
  return fetch(path, { ...options, headers });
}

function showAuth() {
  closeViewer();
  signedIn.value = false;
  pendingSetup.value = false;
  pendingMfaToken.value = null;
  showView('auth');
}

function showList() {
  if (pendingSetup.value) {
    showSecurity();
    return;
  }
  signedIn.value = true;
  showView('list');
  loadDevices();
  updateAlerts();
}

async function submitMfa() {
  if (!pendingMfaToken.value || !mfaCode.value.trim()) return;
  mfaBusy.value = true;
  try {
    const response = await api('/api/auth/mfa', {
      method: 'POST',
      body: JSON.stringify({ mfaToken: pendingMfaToken.value, code: mfaCode.value.trim() }),
    });
    if (!response.ok) {
      const data = await response.clone().json().catch(() => ({}));
      if (data.error === 'mfa_expired') {
        showAuth();
        setAuthMessage(MESSAGES.mfa_expired, true);
        return;
      }
      setMfaMessage(await errorText(response), true);
      return;
    }
    pendingMfaToken.value = null;
    mfaCode.value = '';
    signedIn.value = true;
    showList();
  } catch {
    setMfaMessage('Sem conexão com o servidor.', true);
  } finally {
    mfaBusy.value = false;
  }
}

async function logout() {
  try {
    await api('/api/auth/logout', { method: 'POST' });
  } catch {
    // O cookie expira mesmo se o logout estiver sem conexão.
  }
  showAuth();
}

function resetSecurityForms() {
  setupDetails.value = false;
  setupQr.value = '';
  setupSecret.value = '';
  enableCode.value = '';
  disableCode.value = '';
  recoveryCodes.value = [];
  recoveryVisible.value = false;
  setSecurityMessage('');
}

async function showSecurity() {
  resetSecurityForms();
  showView('security');
  securityStatus.value = 'Carregando…';
  try {
    const response = await api('/api/auth/me');
    if (response.status === 401) {
      showAuth();
      return;
    }
    account.value = await response.json();
    signedIn.value = true;
    pendingSetup.value = account.value.pending;
    securityStatus.value = `Conta: ${account.value.email}. Verificação em duas etapas ${account.value.twoFactor ? 'ativa' : 'desativada'}.`;
  } catch {
    securityStatus.value = 'Não foi possível carregar os dados da conta.';
  }
}

async function startSetup() {
  securityBusy.value = true;
  try {
    const response = await api('/api/account/2fa/setup', { method: 'POST' });
    if (!response.ok) throw new Error(await errorText(response));
    const data = await response.json();
    setupQr.value = data.qr;
    setupSecret.value = data.secret;
    setupDetails.value = true;
  } catch (error) {
    setSecurityMessage(error.message, true);
  } finally {
    securityBusy.value = false;
  }
}

async function enableTwoFactor() {
  securityBusy.value = true;
  try {
    const response = await api('/api/account/2fa/enable', {
      method: 'POST',
      body: JSON.stringify({ code: enableCode.value.trim() }),
    });
    if (!response.ok) throw new Error(await errorText(response));
    recoveryCodes.value = (await response.json()).recoveryCodes;
    setupDetails.value = false;
    setupQr.value = '';
    recoveryVisible.value = true;
  } catch (error) {
    setSecurityMessage(error.message, true);
  } finally {
    securityBusy.value = false;
  }
}

function finishRecovery() {
  recoveryVisible.value = false;
  pendingSetup.value = false;
  showList();
}

async function disableTwoFactor() {
  securityBusy.value = true;
  try {
    const response = await api('/api/account/2fa/disable', {
      method: 'POST',
      body: JSON.stringify({ code: disableCode.value.trim() }),
    });
    if (!response.ok) throw new Error(await errorText(response));
    setSecurityMessage('Verificação em duas etapas desativada.');
    await showSecurity();
  } catch (error) {
    setSecurityMessage(error.message, true);
  } finally {
    securityBusy.value = false;
  }
}

function healthText(health) {
  if (!health) return '';
  const parts = [];
  if (health.battery !== null) parts.push(`Bateria ${health.battery}%${health.charging ? ' (carregando)' : ''}`);
  if (health.temperature !== null) parts.push(`${Math.round(health.temperature)} °C`);
  if (health.throttled) parts.push('quente, qualidade reduzida');
  return parts.join(' · ');
}

function healthWarn(health) {
  return Boolean(health && ((health.battery !== null && health.battery < LOW_BATTERY && !health.charging) || health.throttled));
}

function formatWhen(at) {
  const date = new Date(at);
  const time = date.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  if (date.toDateString() === new Date().toDateString()) return `hoje às ${time}`;
  return `${date.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })} às ${time}`;
}

function beginRename(device) {
  editingDeviceId.value = device.id;
  editName.value = device.name;
  deviceErrors.value = { ...deviceErrors.value, [device.id]: '' };
}

function cancelRename() {
  editingDeviceId.value = null;
}

async function saveName(device) {
  deviceBusyId.value = device.id;
  try {
    const response = await api(`/api/devices/${encodeURIComponent(device.id)}`, {
      method: 'PATCH',
      body: JSON.stringify({ name: editName.value }),
    });
    if (!response.ok) throw new Error(await errorText(response));
    editingDeviceId.value = null;
    await loadDevices();
  } catch (error) {
    deviceErrors.value = { ...deviceErrors.value, [device.id]: error.message };
  } finally {
    deviceBusyId.value = null;
  }
}

async function removeDevice(device) {
  if (!window.confirm(`Remover "${device.name}" e revogar o acesso deste aparelho?`)) return;
  deviceBusyId.value = device.id;
  try {
    const response = await api(`/api/devices/${encodeURIComponent(device.id)}`, { method: 'DELETE' });
    if (!response.ok) throw new Error(await errorText(response));
    await loadDevices();
  } catch (error) {
    deviceErrors.value = { ...deviceErrors.value, [device.id]: error.message };
  } finally {
    deviceBusyId.value = null;
  }
}

async function loadDevices() {
  clearTimeout(listTimer);
  try {
    const response = await api('/api/devices');
    connectionOk.value = true;
    if (response.status === 401) {
      showAuth();
      return;
    }
    if (response.status === 403) {
      pendingSetup.value = true;
      showSecurity();
      return;
    }
    devices.value = (await response.json()).devices;
    listError.value = '';
    const target = openCameraId.value && devices.value.find((device) => device.id === openCameraId.value);
    openCameraId.value = null;
    if (target) {
      openViewer(target);
      return;
    }
  } catch {
    connectionOk.value = false;
    listError.value = 'Não foi possível atualizar a lista de câmeras.';
  }
  listTimer = setTimeout(() => {
    if (view.value === 'list') loadDevices();
  }, LIST_REFRESH_MS);
}

async function registerServiceWorker() {
  if (!pushSupported) return;
  try {
    swRegistration = await navigator.serviceWorker.register('/sw.js');
    await updateAlerts();
  } catch {
    swRegistration = null;
    alertsVisible.value = false;
  }
}

async function updateAlerts() {
  if (!swRegistration) {
    alertsVisible.value = false;
    return;
  }
  alertsVisible.value = true;
  const subscription = await swRegistration.pushManager.getSubscription();
  alertsButtonHidden.value = Notification.permission === 'denied';
  if (Notification.permission === 'denied') {
    alertsText.value = 'Alertas bloqueados nas configurações do navegador.';
  } else if (subscription) {
    alertsText.value = 'Alertas de movimento ativados neste aparelho.';
    alertsButtonText.value = 'Desativar';
  } else {
    alertsText.value = 'Receba um aviso neste aparelho quando houver movimento.';
    alertsButtonText.value = 'Ativar alertas';
  }
}

function base64UrlToBytes(text) {
  const base64 = (text + '='.repeat((4 - (text.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  return Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
}

async function toggleAlerts() {
  alertsBusy.value = true;
  let error = '';
  try {
    const current = await swRegistration.pushManager.getSubscription();
    if (current) {
      await api('/api/push/unsubscribe', { method: 'POST', body: JSON.stringify({ endpoint: current.endpoint }) });
      await current.unsubscribe();
    } else if ((await Notification.requestPermission()) === 'granted') {
      const { publicKey } = await (await fetch('/api/push/key')).json();
      const subscription = await swRegistration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: base64UrlToBytes(publicKey),
      });
      const response = await api('/api/push/subscribe', {
        method: 'POST',
        body: JSON.stringify(subscription.toJSON()),
      });
      if (!response.ok) {
        await subscription.unsubscribe();
        error = await errorText(response);
      }
    }
  } catch {
    error = 'Não foi possível mudar os alertas neste navegador.';
  } finally {
    alertsBusy.value = false;
  }
  await updateAlerts();
  if (error) alertsText.value = error;
}

function releaseFrame() {
  if (lastFrameUrl) URL.revokeObjectURL(lastFrameUrl);
  lastFrameUrl = null;
  frameUrl.value = '';
}

function closeViewer() {
  viewerClosing = true;
  clearTimeout(viewerRetryTimer);
  if (viewerSocket) viewerSocket.close();
  viewerSocket = null;
  viewerDevice.value = null;
  viewerHealth.value = null;
  releaseFrame();
}

async function loadEvents(deviceId) {
  events.value = [];
  eventsEmpty.value = false;
  try {
    const response = await api(`/api/devices/${encodeURIComponent(deviceId)}/events`);
    if (!response.ok || viewerDevice.value?.id !== deviceId) return;
    events.value = (await response.json()).events;
    eventsEmpty.value = events.value.length === 0;
  } catch {
    // Eventos serão carregados novamente quando a câmera for aberta.
  }
}

function connectViewer() {
  const device = viewerDevice.value;
  if (!device) return;
  viewerClosing = false;
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  const socket = new WebSocket(`${scheme}://${location.host}/ws/view?device=${encodeURIComponent(device.id)}`);
  socket.binaryType = 'arraybuffer';
  viewerSocket = socket;

  socket.onmessage = (event) => {
    if (typeof event.data !== 'string') {
      releaseFrame();
      lastFrameUrl = URL.createObjectURL(new Blob([event.data], { type: 'image/jpeg' }));
      frameUrl.value = lastFrameUrl;
      viewerStatus.value = '';
      return;
    }
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }
    if (message.type === 'status') {
      if (!message.online) {
        viewerStatus.value = 'Câmera offline. Aguardando conexão…';
        viewerHealth.value = null;
      } else if (!frameUrl.value) {
        viewerStatus.value = 'Conectado. Aguardando imagem…';
      }
    } else if (message.type === 'health') {
      viewerHealth.value = message.health;
    } else if (message.type === 'motion') {
      events.value.unshift(message.event);
      eventsEmpty.value = false;
    }
  };

  socket.onclose = (event) => {
    if (viewerClosing) return;
    if (event.code === 1008) {
      closeViewer();
      showList();
      return;
    }
    viewerStatus.value = 'Conexão perdida. Reconectando…';
    viewerRetryTimer = setTimeout(() => {
      if (viewerDevice.value && !viewerClosing) connectViewer();
    }, RECONNECT_MS);
  };
}

function openViewer(device) {
  closeViewer();
  viewerDevice.value = device;
  viewerHealth.value = device.health;
  viewerStatus.value = 'Conectando…';
  showView('viewer');
  connectViewer();
  loadEvents(device.id);
}

function backToList() {
  closeViewer();
  showList();
}

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
  registerServiceWorker();
  try {
    await fetch('/api/config');
    connectionOk.value = true;
  } catch {
    connectionOk.value = false;
  }

  if (fragment?.mfaToken) {
    pendingMfaToken.value = fragment.mfaToken;
    showView('mfa');
    return;
  }

  try {
    const response = await api('/api/auth/me');
    if (response.ok) {
      account.value = await response.json();
      signedIn.value = true;
      pendingSetup.value = account.value.pending;
      if (pendingSetup.value) await showSecurity();
      else showList();
      return;
    }
  } catch {
    connectionOk.value = false;
  }

  showAuth();
  if (fragment?.error) setAuthMessage(MESSAGES[fragment.error] || MESSAGES['error=google'], true);
}

onMounted(initialize);
onBeforeUnmount(() => {
  clearTimeout(listTimer);
  closeViewer();
});
</script>

<template>
  <header>
    <h1>Monitor de Câmera</h1>
    <div class="header-actions">
      <span class="badge" :class="connectionOk ? 'ok' : 'err'">
        {{ connectionOk ? 'Servidor conectado' : 'Sem conexão' }}
      </span>
      <button v-if="securityVisible" type="button" class="secondary" @click="showSecurity">Segurança</button>
      <button v-if="logoutVisible" type="button" class="secondary" @click="logout">Sair</button>
    </div>
  </header>

  <main>
    <section v-if="view === 'auth'" class="panel">
      <h2>Entrar</h2>
      <p class="hint">Entre com sua conta Google. Depois, confirme o código de verificação em duas etapas.</p>
      <a class="button google" href="/api/auth/google/start">Entrar com Google</a>
      <p v-if="authMessage" class="hint" :class="{ error: authError }">{{ authMessage }}</p>
    </section>

    <section v-else-if="view === 'mfa'" class="panel">
      <h2>Código de verificação</h2>
      <p class="hint">Digite o código de 6 números do seu app autenticador, ou um código de recuperação.</p>
      <form @submit.prevent="submitMfa">
        <input v-model="mfaCode" type="text" inputmode="numeric" autocomplete="one-time-code" placeholder="000000" maxlength="9" required>
        <div class="actions">
          <button type="submit" :disabled="mfaBusy">Confirmar</button>
          <button type="button" class="secondary" :disabled="mfaBusy" @click="showAuth">Cancelar</button>
        </div>
      </form>
      <p v-if="mfaMessage" class="hint" :class="{ error: mfaError }">{{ mfaMessage }}</p>
    </section>

    <section v-else-if="view === 'security'" class="panel">
      <h2>Segurança da conta</h2>
      <p v-if="pendingSetup" class="hint">Para usar o monitor, ative a verificação em duas etapas agora.</p>
      <p class="hint">{{ securityStatus }}</p>

      <div v-if="account && !account.twoFactor">
        <button v-if="!setupDetails && !recoveryVisible" type="button" :disabled="securityBusy" @click="startSetup">
          Ativar verificação em duas etapas
        </button>
        <div v-if="setupDetails">
          <p class="hint">Escaneie o QR code com seu app autenticador (Google Authenticator, Authy, 1Password…) ou digite a chave manualmente.</p>
          <img :src="setupQr" alt="QR code do 2FA" width="220" height="220">
          <p class="hint">Chave: <code>{{ setupSecret }}</code></p>
          <form @submit.prevent="enableTwoFactor">
            <input v-model="enableCode" type="text" inputmode="numeric" autocomplete="one-time-code" placeholder="000000" maxlength="6" required>
            <div class="actions">
              <button type="submit" :disabled="securityBusy">Confirmar e ativar</button>
            </div>
          </form>
        </div>
        <div v-if="recoveryVisible">
          <p class="hint"><strong>Guarde estes códigos de recuperação.</strong> Cada um vale uma vez e não serão mostrados de novo.</p>
          <ul class="codes">
            <li v-for="code in recoveryCodes" :key="code">{{ code }}</li>
          </ul>
          <div class="actions">
            <button type="button" @click="finishRecovery">Já guardei</button>
          </div>
        </div>
      </div>

      <div v-else-if="account && account.twoFactor">
        <form @submit.prevent="disableTwoFactor">
          <input v-model="disableCode" type="text" inputmode="numeric" autocomplete="one-time-code" placeholder="Código do app ou de recuperação" required>
          <div class="actions">
            <button type="submit" class="danger" :disabled="securityBusy">Desativar verificação em duas etapas</button>
          </div>
        </form>
      </div>

      <p v-if="securityMessage" class="hint" :class="{ error: securityError }">{{ securityMessage }}</p>
      <div v-if="!pendingSetup" class="actions">
        <button type="button" class="secondary" @click="showList">← Voltar</button>
      </div>
    </section>

    <section v-else-if="view === 'list'">
      <div v-if="alertsVisible" class="alerts">
        <span class="hint">{{ alertsText }}</span>
        <button
          type="button"
          class="secondary"
          :hidden="alertsButtonHidden"
          :disabled="alertsBusy"
          @click="toggleAlerts"
        >{{ alertsButtonText }}</button>
      </div>
      <div class="cards">
        <article v-for="device in devices" :key="device.id" class="card">
          <div class="card-top">
            <span class="dot" :class="device.online ? 'on' : 'off'"></span>
            <strong>{{ device.name }}</strong>
          </div>
          <span class="hint">{{ device.online ? 'Online' : 'Offline' }}</span>
          <span v-if="healthText(device.health)" class="hint health" :class="{ warn: healthWarn(device.health) }">
            {{ healthText(device.health) }}
          </span>
          <span v-if="device.lastMotionAt" class="hint">Último movimento: {{ formatWhen(device.lastMotionAt) }}</span>
          <button :disabled="!device.online" @click="openViewer(device)">Assistir</button>
          <div class="actions device-actions">
            <button type="button" class="secondary" @click="beginRename(device)">Renomear</button>
            <button type="button" class="danger" :disabled="deviceBusyId === device.id" @click="removeDevice(device)">Remover</button>
          </div>
          <form v-if="editingDeviceId === device.id" class="device-rename-form" @submit.prevent="saveName(device)">
            <input v-model="editName" type="text" maxlength="40" aria-label="Nome da câmera" required>
            <button type="submit" :disabled="deviceBusyId === device.id">Salvar</button>
            <button type="button" class="secondary" @click="cancelRename">Cancelar</button>
          </form>
          <span v-if="deviceErrors[device.id]" class="hint device-message error">{{ deviceErrors[device.id] }}</span>
        </article>
      </div>
      <p v-if="!devices.length && !listError" class="hint">Nenhuma câmera registrada nesta conta. Faça login no app da câmera.</p>
      <p v-if="listError" class="hint error">{{ listError }}</p>
    </section>

    <section v-else-if="view === 'viewer'">
      <div class="viewer-top">
        <button type="button" class="secondary" @click="backToList">← Voltar</button>
        <strong>{{ viewerDevice?.name }}</strong>
        <span v-if="healthText(viewerHealth)" class="hint health" :class="{ warn: healthWarn(viewerHealth) }">
          {{ healthText(viewerHealth) }}
        </span>
      </div>
      <div class="stage">
        <img v-if="frameUrl" id="frame" :src="frameUrl" alt="Transmissão ao vivo">
        <p v-else class="hint">{{ viewerStatus }}</p>
      </div>
      <h2 class="events-title">Últimos movimentos</h2>
      <p v-if="eventsEmpty" class="hint">Nenhum movimento registrado.</p>
      <ul class="events">
        <li v-for="event in events" :key="event.id" class="event">
          <a v-if="event.snapshot" :href="`/api/events/${encodeURIComponent(event.id)}/snapshot`" target="_blank" rel="noopener">
            <img :src="`/api/events/${encodeURIComponent(event.id)}/snapshot`" alt="Foto do movimento" loading="lazy">
          </a>
          <span class="hint">{{ formatWhen(event.at) }}</span>
        </li>
      </ul>
    </section>
  </main>
</template>
