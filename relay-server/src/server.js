'use strict';

// Ponto de entrada: lê a configuração, monta o servidor e abre a porta.

const { loadConfig } = require('./config');
const { createApp } = require('./app');

const config = loadConfig();
const app = createApp(config);

app.server.listen(config.port, () => {
  console.log(`Relay ouvindo na porta ${config.port} (${config.publicUrl})`);
  console.log(app.accounts.signupOpen() ? 'Cadastro aberto.' : 'Cadastro fechado (use ALLOW_SIGNUP=1 para abrir).');
  console.log(config.google.enabled
    ? 'Login com Google ativo.'
    : 'Login com Google desativado (GOOGLE_CLIENT_ID/SECRET ausentes).');
});

module.exports = { server: app.server };
