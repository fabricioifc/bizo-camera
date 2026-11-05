(() => {
  'use strict';

  // O token de sessão fica num cookie HttpOnly: esta página nunca o lê nem o guarda.
  const RECONNECT_MS = 3000;
  const VIEWS = ['auth-view', 'mfa-view', 'security-view', 'list-view', 'viewer-view'];

  const els = {
    connection: document.getElementById('connection'),
    security: document.getElementById('security'),
    logout: document.getElementById('logout'),
    authMessage: document.getElementById('auth-message'),
    googleButton: document.getElementById('google-button'),
    appLink: document.getElementById('app-link'),
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
    devices: document.getElementById('devices'),
    empty: document.getElementById('empty'),
    viewerView: document.getElementById('viewer-view'),
    back: document.getElementById('back'),
    title: document.getElementById('viewer-title'),
    frame: document.getElementById('frame'),
    status: document.getElementById('viewer-status'),
  };

  let viewer = null; // { device, ws, closing, retryTimer }
  let lastFrameUrl = null;
  let pendingMfaToken = null;
  let signedIn = false;
  let pendingSetup = false; // sessão do Google sem 2FA: só a tela de segurança é liberada
  // ?app=1: o login veio do app Android, que recebe a sessão pelo link monitor://.
  const appMode = new URLSearchParams(location.search).get('app') === '1';

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

  // Login concluído: no modo app, devolve a sessão ao app; na web, mostra a lista.
  function finishAuth() {
    if (appMode && !pendingSetup) return handoffToApp();
    return showList();
  }

  async function handoffToApp() {
    signedIn = true;
    showView('auth-view');
    setMessage(els.authMessage, 'Voltando para o app…');
    try {
      const res = await api('/api/auth/app-code', { method: 'POST' });
      if (!res.ok) throw new Error(await errorText(res));
      const { code } = await res.json();
      const link = `monitor://auth?code=${encodeURIComponent(code)}`;
      els.appLink.href = link;
      els.appLink.hidden = false;
      els.googleButton.hidden = true;
      location.href = link;
    } catch (err) {
      setMessage(els.authMessage, err.message, true);
    }
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
      finishAuth();
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
    finishAuth();
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

    const button = document.createElement('button');
    button.textContent = 'Assistir';
    button.disabled = !device.online;
    button.addEventListener('click', () => openViewer(device));

    card.append(top, state, button);
    return card;
  }

  async function loadDevices() {
    els.devices.replaceChildren();
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
      for (const device of devices) els.devices.append(deviceCard(device));
    } catch {
      setConnection(false);
    }
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
          if (!msg.online) setViewerStatus('Câmera offline. Aguardando conexão…');
          else if (els.frame.hidden) setViewerStatus('Conectado. Aguardando imagem…');
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
    els.frame.hidden = true;
    setViewerStatus('Conectando…');
    showView('viewer-view');
    connectViewer();
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
    return null;
  }

  async function init() {
    const fragment = readFragment();
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

    if (appMode) els.googleButton.href = '/api/auth/google/start?app=1';

    try {
      const res = await api('/api/auth/me');
      if (res.ok) {
        const me = await res.json();
        signedIn = true;
        pendingSetup = me.pending;
        return finishAuth();
      }
    } catch {
      setConnection(false);
    }

    showAuth();
    if (fragment?.error) setMessage(els.authMessage, MESSAGES[fragment.error] || MESSAGES['error=google'], true);
  }

  init();
})();
