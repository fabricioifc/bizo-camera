(() => {
  'use strict';

  const TOKEN_KEY = 'monitorSession';
  const RECONNECT_MS = 3000;

  const els = {
    connection: document.getElementById('connection'),
    logout: document.getElementById('logout'),
    authView: document.getElementById('auth-view'),
    authForm: document.getElementById('auth-form'),
    email: document.getElementById('email'),
    password: document.getElementById('password'),
    authMessage: document.getElementById('auth-message'),
    signupButton: document.getElementById('signup-button'),
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

  function getToken() {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  }

  function saveToken(token) {
    try {
      localStorage.setItem(TOKEN_KEY, token);
    } catch {
      // Navegador sem armazenamento: o login vale só nesta aba.
    }
  }

  function clearToken() {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      // nada a fazer
    }
  }

  function setConnection(ok) {
    els.connection.textContent = ok ? 'Servidor conectado' : 'Sem conexão';
    els.connection.className = `badge ${ok ? 'ok' : 'err'}`;
  }

  function setAuthMessage(text, isError = false) {
    els.authMessage.textContent = text;
    els.authMessage.classList.toggle('error', isError);
  }

  const MESSAGES = {
    invalid_credentials: 'E-mail ou senha incorretos.',
    invalid_email: 'Digite um e-mail válido.',
    weak_password: 'A senha precisa ter pelo menos 8 caracteres.',
    email_taken: 'Já existe uma conta com este e-mail.',
    signup_disabled: 'O cadastro está fechado. Peça acesso ao dono do servidor.',
    too_many_requests: 'Muitas tentativas. Aguarde um minuto.',
  };

  // Chamadas à API: o token de sessão vai no cabeçalho Authorization.
  async function api(path, options = {}) {
    const headers = { ...(options.headers || {}) };
    const token = getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    if (options.body) headers['Content-Type'] = 'application/json';
    return fetch(path, { ...options, headers });
  }

  async function errorMessage(res) {
    try {
      const data = await res.json();
      return MESSAGES[data.error] || 'Não foi possível concluir a ação.';
    } catch {
      return 'Não foi possível concluir a ação.';
    }
  }

  function showAuth() {
    closeViewer();
    els.listView.hidden = true;
    els.viewerView.hidden = true;
    els.authView.hidden = false;
    els.logout.hidden = true;
    els.signupButton.hidden = !signupOpen;
  }

  function showList() {
    els.authView.hidden = true;
    els.viewerView.hidden = true;
    els.listView.hidden = false;
    els.logout.hidden = false;
    loadDevices();
  }

  async function login(email, password) {
    const res = await api('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
    if (!res.ok) throw new Error(await errorMessage(res));
    saveToken((await res.json()).token);
  }

  async function signup(email, password) {
    const res = await api('/api/auth/signup', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
    if (!res.ok) throw new Error(await errorMessage(res));
    saveToken((await res.json()).token);
  }

  async function submitAuth(action) {
    const email = els.email.value.trim();
    const password = els.password.value;
    if (!email || password.length < 8) {
      setAuthMessage('Preencha e-mail e senha (mínimo 8 caracteres).', true);
      return;
    }
    try {
      await action(email, password);
      els.password.value = '';
      setAuthMessage('');
      showList();
    } catch (err) {
      setAuthMessage(err.message, true);
    }
  }

  els.authForm.addEventListener('submit', (event) => {
    event.preventDefault();
    submitAuth(login);
  });

  els.signupButton.addEventListener('click', () => submitAuth(signup));

  els.logout.addEventListener('click', async () => {
    try {
      await api('/api/auth/logout', { method: 'POST' });
    } catch {
      // Mesmo sem rede, o token local é apagado.
    }
    clearToken();
    showAuth();
  });

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
      if (res.status === 401) {
        clearToken();
        showAuth();
        return;
      }
      const { devices } = await res.json();
      els.empty.hidden = devices.length > 0;
      for (const device of devices) els.devices.append(deviceCard(device));
    } catch {
      setConnection(false);
    }
  }

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
    const url = `${scheme}://${location.host}/ws/view` +
      `?token=${encodeURIComponent(getToken() || '')}` +
      `&device=${encodeURIComponent(current.device.id)}`;

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
    els.listView.hidden = true;
    els.viewerView.hidden = false;
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

  async function init() {
    try {
      const res = await fetch('/api/config');
      const config = await res.json();
      signupOpen = config.signupOpen;
      setConnection(true);
    } catch {
      setConnection(false);
    }

    if (getToken()) showList();
    else showAuth();
  }

  init();
})();
