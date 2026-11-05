'use strict';

const fs = require('node:fs');
const path = require('node:path');
const webpush = require('web-push');

/**
 * Notificações Web Push (alertas de movimento no navegador, mesmo com o site fechado).
 * As chaves VAPID são geradas na primeira execução e guardadas ao lado do banco.
 */
function setupPush({ dataDir, publicUrl }) {
  const file = path.join(dataDir, 'vapid.json');
  let keys = null;
  try {
    keys = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (err) {
    if (err.code !== 'ENOENT') throw err;
  }
  if (!keys) {
    keys = webpush.generateVAPIDKeys();
    fs.mkdirSync(dataDir, { recursive: true });
    fs.writeFileSync(file, JSON.stringify(keys), { mode: 0o600 });
  }
  // O "subject" identifica quem envia; precisa ser https:// ou mailto:.
  const subject = publicUrl.startsWith('https://') ? publicUrl : 'mailto:monitor@example.com';
  webpush.setVapidDetails(subject, keys.publicKey, keys.privateKey);

  return {
    publicKey: keys.publicKey,

    // Envia a notificação. Devolve false se a assinatura não existe mais (o navegador a cancelou).
    async send(subscription, payload) {
      try {
        await webpush.sendNotification(subscription, JSON.stringify(payload), { TTL: 300, urgency: 'high' });
        return true;
      } catch (err) {
        if (err.statusCode === 404 || err.statusCode === 410) return false;
        console.warn('Falha ao enviar notificação:', err.statusCode || err.message);
        return true;
      }
    },
  };
}

module.exports = { setupPush };
