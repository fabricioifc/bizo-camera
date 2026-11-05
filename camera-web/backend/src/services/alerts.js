'use strict';

const MAX_PUSH_SUBSCRIPTIONS = 10;

function isValidSubscription(subscription) {
  return Boolean(
    subscription &&
      typeof subscription.endpoint === 'string' &&
      subscription.endpoint.startsWith('https://') &&
      subscription.endpoint.length < 2000 &&
      subscription.keys &&
      typeof subscription.keys.p256dh === 'string' &&
      typeof subscription.keys.auth === 'string' &&
      subscription.keys.p256dh.length < 200 &&
      subscription.keys.auth.length < 100,
  );
}

/** Alertas de movimento por Web Push: assinaturas dos navegadores de cada usuário. */
function createAlerts({ store, push }) {
  const subscriptionsOf = (userId) => store.data.push[userId] || [];

  // Envia a todos os navegadores do usuário e remove as assinaturas que o navegador cancelou.
  async function send(userId, payload) {
    const subscriptions = subscriptionsOf(userId);
    if (subscriptions.length === 0) return;
    const delivered = await Promise.all(subscriptions.map((subscription) => push.send(subscription, payload)));
    const alive = subscriptions.filter((_, index) => delivered[index]);
    if (alive.length !== subscriptions.length) {
      store.data.push[userId] = alive;
      store.save();
    }
  }

  return {
    publicKey: push.publicKey,

    /** Guarda a assinatura deste navegador. Devolve false se ela for inválida. */
    subscribe(userId, body) {
      if (!isValidSubscription(body)) return false;
      const subscription = { endpoint: body.endpoint, keys: { p256dh: body.keys.p256dh, auth: body.keys.auth } };
      const others = subscriptionsOf(userId).filter((item) => item.endpoint !== subscription.endpoint);
      store.data.push[userId] = [...others, subscription].slice(-MAX_PUSH_SUBSCRIPTIONS);
      store.save();
      return true;
    },

    unsubscribe(userId, endpoint) {
      store.data.push[userId] = subscriptionsOf(userId).filter((item) => item.endpoint !== endpoint);
      store.save();
    },

    /** Avisa o dono da câmera de um movimento. */
    notifyMotion(deviceId, device, event) {
      return send(device.ownerId, {
        kind: 'motion', title: 'Movimento detectado', device: device.name, deviceId, at: event.at, eventId: event.id,
      });
    },

    /** Avisa o dono sobre a câmera (offline, de volta, fora da tomada, bateria baixa, quente). */
    notifyDevice(deviceId, device, { kind, title, body, at }) {
      return send(device.ownerId, { kind, title, body, device: device.name, deviceId, at });
    },
  };
}

module.exports = { createAlerts };
