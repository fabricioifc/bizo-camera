'use strict';

const path = require('node:path');

/** Lê a configuração das variáveis de ambiente (veja a tabela no README). */
function loadConfig(env = process.env) {
  const port = Number(env.PORT || 8787);
  const publicUrl = (env.PUBLIC_URL || `http://localhost:${port}`).replace(/\/+$/, '');
  const dataFile = env.DATA_FILE || path.join(__dirname, '..', '..', 'data', 'db.json');
  const dataDir = path.dirname(dataFile);
  const googleClientId = env.GOOGLE_CLIENT_ID || '';
  const googleClientSecret = env.GOOGLE_CLIENT_SECRET || '';

  return {
    port,
    publicUrl,
    allowSignup: env.ALLOW_SIGNUP === '1',
    trustProxy: env.TRUST_PROXY === '1',
    require2fa: env.REQUIRE_2FA !== '0',
    // Cookie "Secure" só quando o site é HTTPS; em teste local (http) o navegador ignoraria o cookie.
    secureCookies: publicUrl.startsWith('https://'),
    issuer: 'Monitor de Câmera',
    google: {
      clientId: googleClientId,
      clientSecret: googleClientSecret,
      enabled: Boolean(googleClientId && googleClientSecret),
    },
    // Pacote do app aceito nas atualizações (o build debug usa o sufixo .debug).
    appPackage: env.APP_PACKAGE || 'br.dev.bizo.cameramonitor',
    dataFile,
    dataDir,
    snapshotDir: path.join(dataDir, 'snapshots'),
    // Página compilada pelo frontend (npm run build em frontend/).
    webDir: env.WEB_DIR || path.join(__dirname, '..', '..', 'frontend', 'dist'),
  };
}

module.exports = { loadConfig };
