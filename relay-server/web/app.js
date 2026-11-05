(() => {
  'use strict';

  // O token de sessão fica num cookie HttpOnly: esta página nunca o lê nem o guarda.
  const RECONNECT_MS = 3000;
  const LIST_REFRESH_MS = 30_000;
  const LOW_BATTERY = 20;
  const VIEWS = ['auth-view', 'mfa-view', 'security-view', 'list-view', 'viewer-view'];

  const els = {
    connection: document.getElementById('connection'),
    security: document.getElementById('security'),
    logout: document.getElementById('logout'),
    authMessage: document.getElementById('auth-message'),
    mfaForm: document.getElementById('mfa-form'),
    mfaCode: document.getElementById('mfa-code'),
    mfaCancel: document.getElementById('mfa-cancel'),
    mfaMessage: document.getElementById('mfa-message'),
    pendingNote: document.getElementById('pending-note'),
    securityStatus: document.getElementById('security-status'),
    setupSection: document.getElementById('setup-section'),
    setupButton: document.getElementById('setup-button'),
    setupDetails: document.getElementById('setup-details'),
    setupQr: document.getElementById('setup-qr'),
    setupSecret: document.getElementById('setup-secret'),
    enableForm: document.getElementById('enable-form'),
    enableCode: document.getElementById('enable-code'),
    recoverySection: document.getElementById('recovery-section'),
    recoveryList: document.getElementById('recovery-list'),
    recoveryDone: document.getElementById('recovery-done'),
    disableSection: document.getElementById('disable-section'),
    disableForm: document.getElementById('disable-form'),
    disableCode: document.getElementById('disable-code'),
    securityMessage: document.getElementById('security-message'),
    securityBack: document.getElementById('security-back'),
    listView: document.getElementById('list-view'),
    alerts: document.getElementById('alerts'),
    alertsText: document.getElementById('alerts-text'),
    alertsButton: document.getElementById('alerts-button'),
    devices: document.getElementById('devices'),
    empty: document.getElementById('empty'),
    viewerView: document.getElementById('viewer-view'),
    back: document.getElementById('back'),
    title: document.getElementById('viewer-title'),
    viewerHealth: document.getElementById('viewer-health'),
    frame: document.getElementById('frame'),
    status: document.getElementById('viewer-status'),
    events: document.getElementById('events'),
    eventsEmpty: document.getElementById('events-empty'),
  };

  const pushSupported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  let swRegistration = null;
  let openCameraId = null; // câmera a abrir depois de carregar a lista (vindo de uma notificação)

  let viewer = null; // { device, ws, closing, retryTimer }
  let listTimer = null;
  let lastFrameUrl = null;
  let pendingMfaToken = null;
  let signedIn = false;
  let pendingSetup = false; // sessão do Google sem 2FA: só a tela de segurança é liberada

  const MESSAGES = {
    account_locked: 'Conta bloqueada por 15 minutos após muitas senhas erradas.',
    invalid_code: 'Código incorreto. Confira o app autenticador e tente de novo.',
    mfa_expired: 'A verificação expirou. Entre com o Google de novo.',
    too_many_requests: 'Muitas tentativas. Aguarde um minuto.',
    already_enabled: 'A verificação em duas etapas já está ativa.',
    setup_required: 'Comece a configuração antes de confirmar o código.',
    not_enabled: 'A verificação em duas etapas não está ativa.',
    invalid_credentials: 'Senha incorreta.',
    'error=google': 'Não foi possível entrar com o Google. Tente de novo.',
    'error=signup_disabled': 'O cadastro está fechado. Peça acesso ao dono do servidor.',
  };

  function setConnection(ok) {
    els.connection.textContent = ok ? 'Servidor conectado' : 'Sem conexão';
    els.connection.className = `badge ${ok ? 'ok' : 'err'}`;
  }

  function setMessage(el, text, isError = false) {
    el.textContent = text;
    el.classList.toggle('error', isError);
  }

  async function errorText(res) {
    const data = await res.json().catch(() => ({}));
    return MESSAGES[data.error] || 'Não foi possível concluir a ação.';
  }

  // Chamadas à API: o cookie de sessão vai junto automaticamente (mesma origem).
  function api(path, options = {}) {
    const headers = { ...(options.headers || {}) };
    if (options.body) headers['Content-Type'] = 'application/json';
    return fetch(path, { ...options, headers });
  }

  function showView(name) {
    for (const id of VIEWS) {
      document.getElementById(id).hidden = id !== name;
    }
    els.logout.hidden = !signedIn || name === 'auth-view' || name === 'mfa-view';
    els.security.hidden = !signedIn || pendingSetup || name !== 'list-view';
  }

  function showAuth() {
    closeViewer();
    signedIn = false;
    pendingSetup = false;
    pendingMfaToken = null;
    showView('auth-view');
  }

  function showList() {
    if (pendingSetup) return showSecurity();
    signedIn = true;
    showView('list-view');
    loadDevices();
  }

  // ----- Segundo fator (depois do login pelo Google) -----

  els.mfaForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const code = els.mfaCode.value.trim();
    if (!pendingMfaToken || !code) return;
    els.mfaCancel.disabled = true;
    try {
      const res = await api('/api/auth/mfa', {
        method: 'POST',
        body: JSON.stringify({ mfaToken: pendingMfaToken, code }),
      });
      if (!res.ok) {
        const data = await res.clone().json().catch(() => ({}));
        if (data.error === 'mfa_expired') {
          showAuth();
          setMessage(els.authMessage, MESSAGES.mfa_expired, true);
          return;
        }
        setMessage(els.mfaMessage, await errorText(res), true);
        return;
      }
      pendingMfaToken = null;
      els.mfaCode.value = '';
      signedIn = true;
      showList();
    } catch {
      setMessage(els.mfaMessage, 'Sem conexão com o servidor.', true);
    } finally {
      els.mfaCancel.disabled = false;
    }
  });

  els.mfaCancel.addEventListener('click', showAuth);

  // ----- Sair -----

  els.logout.addEventListener('click', async () => {
    try {
      await api('/api/auth/logout', { method: 'POST' });
    } catch {
      // Sem rede, o cookie continua até expirar; a tela volta para o login mesmo assim.
    }
    showAuth();
  });

  // ----- Segurança da conta -----

  function resetSecurityForms() {
    els.setupSection.hidden = true;
    els.setupDetails.hidden = true;
    els.recoverySection.hidden = true;
    els.disableSection.hidden = true;
    els.setupQr.removeAttribute('src');
    els.setupSecret.textContent = '';
    els.recoveryList.replaceChildren();
    els.enableCode.value = '';
    els.disableCode.value = '';
    setMessage(els.securityMessage, '');
  }

  async function showSecurity() {
    resetSecurityForms();
    showView('security-view');
    setMessage(els.securityStatus, 'Carregando…');
    try {
      const res = await api('/api/auth/me');
      if (res.status === 401) return showAuth();
      const me = await res.json();
      signedIn = true;
      pendingSetup = me.pending;
      els.pendingNote.hidden = !me.pending;
      els.securityBack.hidden = me.pending;
      els.logout.hidden = false;
      setMessage(
        els.securityStatus,
        `Conta: ${me.email}. Verificação em duas etapas ${me.twoFactor ? 'ativa' : 'desativada'}.`,
      );
      els.setupSection.hidden = me.twoFactor;
      els.disableSection.hidden = !me.twoFactor;
    } catch {
      setMessage(els.securityStatus, 'Não foi possível carregar os dados da conta.', true);
    }
  }

  els.security.addEventListener('click', showSecurity);
  els.securityBack.addEventListener('click', showList);

  els.setupButton.addEventListener('click', async () => {
    try {
      const res = await api('/api/account/2fa/setup', { method: 'POST' });
      if (!res.ok) throw new Error(await errorText(res));
      const data = await res.json();
      els.setupQr.src = data.qr;
      els.setupSecret.textContent = data.secret;
      els.setupButton.hidden = true;
      els.setupDetails.hidden = false;
      els.enableCode.focus();
    } catch (err) {
      setMessage(els.securityMessage, err.message, true);
    }
  });

  els.enableForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      const res = await api('/api/account/2fa/enable', {
        method: 'POST',
        body: JSON.stringify({ code: els.enableCode.value.trim() }),
      });
      if (!res.ok) throw new Error(await errorText(res));
      const data = await res.json();
      els.setupDetails.hidden = true;
      els.setupQr.removeAttribute('src');
      els.recoveryList.replaceChildren(...data.recoveryCodes.map((code) => {
        const item = document.createElement('li');
        item.textContent = code;
        return item;
      }));
      els.recoverySection.hidden = false;
    } catch (err) {
      setMessage(els.securityMessage, err.message, true);
    }
  });

  // Depois de guardar os códigos, a sessão já está completa: vai para a lista.
  els.recoveryDone.addEventListener('click', () => {
    pendingSetup = false;
    showList();
  });

  els.disableForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      const res = await api('/api/account/2fa/disable', {
        method: 'POST',
        body: JSON.stringify({ code: els.disableCode.value.trim() }),
      });
      if (!res.ok) throw new Error(await errorText(res));
      setMessage(els.securityMessage, 'Verificação em duas etapas desativada.');
      showSecurity();
    } catch (err) {
      setMessage(els.securityMessage, err.message, true);
    }
  });

  // ----- Lista de câmeras -----

  function deviceCard(device) {
    const card = document.createElement('article');
    card.className = 'card';

    const top = document.createElement('div');
    top.className = 'card-top';
    const dot = document.createElement('span');
    dot.className = `dot ${device.online ? 'on' : 'off'}`;
    const name = document.createElement('strong');
    name.textContent = device.name;
    top.append(dot, name);

    const state = document.createElement('span');
    state.className = 'hint';
    state.textContent = device.online ? 'Online' : 'Offline';

    const health = document.createElement('span');
    showHealth(health, device.health);

    const motion = document.createElement('span');
    motion.className = 'hint';
    motion.textContent = device.lastMotionAt ? `Último movimento: ${formatWhen(device.lastMotionAt)}` : '';
    motion.hidden = !device.lastMotionAt;

    const button = document.createElement('button');
    button.textContent = 'Assistir';
    button.disabled = !device.online;
    button.addEventListener('click', () => openViewer(device));

    card.append(top, state, health, motion, button);
    return card;
  }

  function formatWhen(at) {
    const date = new Date(at);
    const time = date.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    if (date.toDateString() === new Date().toDateString()) return `hoje às ${time}`;
    return `${date.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })} às ${time}`;
  }

  // ----- Alertas de movimento (notificações do navegador) -----

  async function registerServiceWorker() {
    if (!pushSupported) return;
    try {
      swRegistration = await navigator.serviceWorker.register('/sw.js');
    } catch {
      swRegistration = null;
    }
  }

  async function updateAlerts() {
    if (!swRegistration) {
      els.alerts.hidden = true;
      return;
    }
    els.alerts.hidden = false;
    const subscription = await swRegistration.pushManager.getSubscription();
    els.alertsButton.hidden = Notification.permission === 'denied';
    if (Notification.permission === 'denied') {
      els.alertsText.textContent = 'Alertas bloqueados nas configurações do navegador.';
    } else if (subscription) {
      els.alertsText.textContent = 'Alertas de movimento ativados neste aparelho.';
      els.alertsButton.textContent = 'Desativar';
    } else {
      els.alertsText.textContent = 'Receba um aviso neste aparelho quando houver movimento.';
      els.alertsButton.textContent = 'Ativar alertas';
    }
  }

  function base64UrlToBytes(text) {
    const base64 = (text + '='.repeat((4 - (text.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
    return Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
  }

  els.alertsButton.addEventListener('click', async () => {
    els.alertsButton.disabled = true;
    let error = null;
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
        const res = await api('/api/push/subscribe', { method: 'POST', body: JSON.stringify(subscription.toJSON()) });
        if (!res.ok) {
          await subscription.unsubscribe();
          error = await errorText(res);
        }
      }
    } catch {
      error = 'Não foi possível mudar os alertas neste navegador.';
    } finally {
      els.alertsButton.disabled = false;
    }
    await updateAlerts();
    if (error) els.alertsText.textContent = error;
  });

  // Bateria e temperatura do celular da câmera. Destaca bateria baixa e aquecimento.
  function showHealth(el, health) {
    el.className = 'hint health';
    if (!health) {
      el.textContent = '';
      el.hidden = true;
      return;
    }
    const parts = [];
    if (health.battery !== null) parts.push(`Bateria ${health.battery}%${health.charging ? ' (carregando)' : ''}`);
    if (health.temperature !== null) parts.push(`${Math.round(health.temperature)} °C`);
    if (health.throttled) parts.push('quente, qualidade reduzida');
    const lowBattery = health.battery !== null && health.battery < LOW_BATTERY && !health.charging;
    el.classList.toggle('warn', lowBattery || health.throttled);
    el.textContent = parts.join(' · ');
    el.hidden = parts.length === 0;
  }

  async function loadDevices() {
    clearTimeout(listTimer);
    try {
      const res = await api('/api/devices');
      setConnection(true);
      if (res.status === 401) return showAuth();
      if (res.status === 403) {
        // Sessão do Google sem 2FA ativo: só a configuração é liberada.
        pendingSetup = true;
        return showSecurity();
      }
      const { devices } = await res.json();
      els.empty.hidden = devices.length > 0;
      els.devices.replaceChildren(...devices.map(deviceCard));
      // Veio de uma notificação: abre direto a câmera que detectou o movimento.
      const target = openCameraId && devices.find((device) => device.id === openCameraId);
      openCameraId = null;
      if (target) return openViewer(target);
    } catch {
      setConnection(false);
    }
    // Atualiza status e saúde das câmeras enquanto a lista estiver aberta.
    listTimer = setTimeout(() => {
      if (!els.listView.hidden) loadDevices();
    }, LIST_REFRESH_MS);
  }

  // ----- Transmissão ao vivo -----

  function showFrame(buffer) {
    const url = URL.createObjectURL(new Blob([buffer], { type: 'image/jpeg' }));
    els.frame.src = url;
    els.frame.hidden = false;
    els.status.hidden = true;
    if (lastFrameUrl) URL.revokeObjectURL(lastFrameUrl);
    lastFrameUrl = url;
  }

  function setViewerStatus(text) {
    els.status.textContent = text;
    els.status.hidden = !text;
  }

  function connectViewer() {
    const current = viewer;
    if (!current) return;

    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${scheme}://${location.host}/ws/view?device=${encodeURIComponent(current.device.id)}`;

    const ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';
    current.ws = ws;

    ws.onmessage = (event) => {
      if (typeof event.data === 'string') {
        const msg = JSON.parse(event.data);
        if (msg.type === 'status') {
          if (!msg.online) {
            setViewerStatus('Câmera offline. Aguardando conexão…');
            showHealth(els.viewerHealth, null);
          } else if (els.frame.hidden) {
            setViewerStatus('Conectado. Aguardando imagem…');
          }
        } else if (msg.type === 'health') {
          showHealth(els.viewerHealth, msg.health);
        } else if (msg.type === 'motion') {
          els.events.prepend(eventItem(msg.event));
          els.eventsEmpty.hidden = true;
        }
        return;
      }
      showFrame(event.data);
    };

    ws.onclose = () => {
      if (current.closing) return;
      setViewerStatus('Conexão perdida. Reconectando…');
      current.retryTimer = setTimeout(() => {
        if (viewer === current && !current.closing) connectViewer();
      }, RECONNECT_MS);
    };
  }

  function openViewer(device) {
    closeViewer();
    viewer = { device, ws: null, closing: false, retryTimer: null };
    els.title.textContent = device.name;
    showHealth(els.viewerHealth, device.health);
    els.frame.hidden = true;
    setViewerStatus('Conectando…');
    showView('viewer-view');
    connectViewer();
    loadEvents(device.id);
  }

  // ----- Últimos movimentos -----

  async function loadEvents(deviceId) {
    els.events.replaceChildren();
    els.eventsEmpty.hidden = true;
    try {
      const res = await api(`/api/devices/${encodeURIComponent(deviceId)}/events`);
      if (!res.ok || viewer?.device.id !== deviceId) return;
      const { events } = await res.json();
      els.events.replaceChildren(...events.map(eventItem));
      els.eventsEmpty.hidden = events.length > 0;
    } catch {
      // Sem rede: a lista fica vazia até abrir a câmera de novo.
    }
  }

  function eventItem(event) {
    const item = document.createElement('li');
    item.className = 'event';
    if (event.snapshot) {
      const link = document.createElement('a');
      link.href = `/api/events/${encodeURIComponent(event.id)}/snapshot`;
      link.target = '_blank';
      link.rel = 'noopener';
      const image = document.createElement('img');
      image.src = link.href;
      image.alt = 'Foto do movimento';
      image.loading = 'lazy';
      link.append(image);
      item.append(link);
    }
    const when = document.createElement('span');
    when.className = 'hint';
    when.textContent = formatWhen(event.at);
    item.append(when);
    return item;
  }

  function closeViewer() {
    if (viewer) {
      viewer.closing = true;
      clearTimeout(viewer.retryTimer);
      if (viewer.ws) viewer.ws.close();
      viewer = null;
    }
    if (lastFrameUrl) {
      URL.revokeObjectURL(lastFrameUrl);
      lastFrameUrl = null;
    }
    els.frame.removeAttribute('src');
    els.frame.hidden = true;
  }

  els.back.addEventListener('click', () => {
    closeViewer();
    showList();
  });

  // ----- Inicialização -----

  // O servidor redireciona para cá com o resultado do login pelo Google, no fragmento da URL.
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

  async function init() {
    const fragment = readFragment();
    if (fragment?.cameraId) openCameraId = fragment.cameraId;
    registerServiceWorker().then(updateAlerts);
    try {
      await fetch('/api/config');
      setConnection(true);
    } catch {
      setConnection(false);
    }

    if (fragment?.mfaToken) {
      pendingMfaToken = fragment.mfaToken;
      showView('mfa-view');
      els.mfaCode.focus();
      return;
    }

    try {
      const res = await api('/api/auth/me');
      if (res.ok) {
        const me = await res.json();
        signedIn = true;
        pendingSetup = me.pending;
        return showList();
      }
    } catch {
      setConnection(false);
    }

    showAuth();
    if (fragment?.error) setMessage(els.authMessage, MESSAGES[fragment.error] || MESSAGES['error=google'], true);
  }

  init();
})();
