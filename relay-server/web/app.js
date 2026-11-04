(() => {
  'use strict';

  // O token de sessão fica num cookie HttpOnly: esta página nunca o lê nem o guarda.
  const RECONNECT_MS = 3000;
  const VIEWS = ['auth-view', 'mfa-view', 'security-view', 'list-view', 'viewer-view'];

  const els = {
    connection: document.getElementById('connection'),
    security: document.getElementById('security'),
    logout: document.getElementById('logout'),
    authForm: document.getElementById('auth-form'),
    email: document.getElementById('email'),
    password: document.getElementById('password'),
    authMessage: document.getElementById('auth-message'),
    loginButton: document.getElementById('login-button'),
    signupButton: document.getElementById('signup-button'),
    googleButton: document.getElementById('google-button'),
    mfaForm: document.getElementById('mfa-form'),
    mfaCode: document.getElementById('mfa-code'),
    mfaCancel: document.getElementById('mfa-cancel'),
    mfaMessage: document.getElementById('mfa-message'),
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
    disablePassword: document.getElementById('disable-password'),
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
  let signupOpen = false;
  let googleEnabled = false;
  let pendingMfaToken = null;
  let signedIn = false;

  const MESSAGES = {
    invalid_credentials: 'E-mail ou senha incorretos.',
    invalid_email: 'Digite um e-mail válido.',
    weak_password: 'A senha precisa ter pelo menos 8 caracteres.',
    email_taken: 'Já existe uma conta com este e-mail.',
    signup_disabled: 'O cadastro está fechado. Peça acesso ao dono do servidor.',
    too_many_requests: 'Muitas tentativas. Aguarde um minuto.',
    account_locked: 'Conta bloqueada por 15 minutos após muitas senhas erradas.',
    invalid_code: 'Código incorreto. Confira o app autenticador e tente de novo.',
    mfa_expired: 'A verificação expirou. Entre de novo.',
    already_enabled: 'A verificação em duas etapas já está ativa.',
    setup_required: 'Comece a configuração antes de confirmar o código.',
    not_enabled: 'A verificação em duas etapas não está ativa.',
    error_google: 'Não foi possível entrar com o Google. Tente de novo.',
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
    try {
      const data = await res.json();
      return MESSAGES[data.error] || 'Não foi possível concluir a ação.';
    } catch {
      return 'Não foi possível concluir a ação.';
    }
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
    els.security.hidden = !signedIn || name !== 'list-view';
  }

  function showAuth() {
    closeViewer();
    signedIn = false;
    pendingMfaToken = null;
    els.signupButton.hidden = !signupOpen;
    els.googleButton.hidden = !googleEnabled;
    showView('auth-view');
  }

  function showList() {
    signedIn = true;
    showView('list-view');
    loadDevices();
  }

  // ----- Entrar, cadastrar e segundo fator -----

  async function handleLoginResponse(res) {
    const data = await res.json();
    if (data.mfaRequired) {
      pendingMfaToken = data.mfaToken;
      els.mfaCode.value = '';
      setMessage(els.mfaMessage, '');
      showView('mfa-view');
      els.mfaCode.focus();
      return;
    }
    els.password.value = '';
    setMessage(els.authMessage, '');
    showList();
  }

  async function submitAuth(action) {
    const email = els.email.value.trim();
    const password = els.password.value;
    if (!email || password.length < 8) {
      setMessage(els.authMessage, 'Preencha e-mail e senha (mínimo 8 caracteres).', true);
      return;
    }
    try {
      const res = await api(action === 'signup' ? '/api/auth/signup' : '/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      });
      if (!res.ok) throw new Error(await errorText(res));
      if (action === 'signup') {
        els.password.value = '';
        showList();
      } else {
        await handleLoginResponse(res);
      }
    } catch (err) {
      setMessage(els.authMessage, err.message, true);
    }
  }

  els.authForm.addEventListener('submit', (event) => {
    event.preventDefault();
    submitAuth('login');
  });

  els.signupButton.addEventListener('click', () => submitAuth('signup'));

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
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        if (data.error === 'mfa_expired') {
          pendingMfaToken = null;
          showAuth();
          setMessage(els.authMessage, MESSAGES.mfa_expired, true);
          return;
        }
        throw new Error(MESSAGES[data.error] || 'Não foi possível concluir a ação.');
      }
      pendingMfaToken = null;
      els.password.value = '';
      showList();
    } catch (err) {
      setMessage(els.mfaMessage, err.message, true);
    } finally {
      els.mfaCancel.disabled = false;
    }
  });

  els.mfaCancel.addEventListener('click', () => {
    pendingMfaToken = null;
    showAuth();
  });

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
    els.disablePassword.value = '';
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
      setMessage(
        els.securityStatus,
        `Conta: ${me.email}. Verificação em duas etapas ${me.twoFactor ? 'ativa' : 'desativada'}.`,
      );
      els.setupSection.hidden = me.twoFactor;
      els.disableSection.hidden = !me.twoFactor;
      els.disablePassword.hidden = !me.hasPassword;
      els.disablePassword.required = me.hasPassword;
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

  els.recoveryDone.addEventListener('click', showSecurity);

  els.disableForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      const res = await api('/api/account/2fa/disable', {
        method: 'POST',
        body: JSON.stringify({
          password: els.disablePassword.value,
          code: els.disableCode.value.trim(),
        }),
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
    let fragment = readFragment();
    try {
      const res = await fetch('/api/config');
      const config = await res.json();
      signupOpen = config.signupOpen;
      googleEnabled = config.googleEnabled;
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
      if (res.ok) return showList();
    } catch {
      setConnection(false);
    }

    showAuth();
    if (fragment?.error) setMessage(els.authMessage, MESSAGES[fragment.error] || MESSAGES.error_google, true);
  }

  init();
})();
