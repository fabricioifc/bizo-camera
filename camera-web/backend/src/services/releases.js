'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { readApkInfo } = require('../lib/apk');
const { sha256 } = require('../lib/crypto');

/**
 * Versão do app publicada para atualização: o APK e os dados dele, na pasta releases/
 * ao lado do banco (no Docker, dentro do volume). Guarda só a última versão enviada.
 */
function createReleases({ dataDir, appPackage }) {
  const dir = path.join(dataDir, 'releases');
  const apkPath = path.join(dir, 'app.apk');
  const infoPath = path.join(dir, 'app.json');

  return {
    apkPath,

    /** Dados da versão publicada, ou null se nenhuma foi enviada. */
    latest() {
      try {
        return JSON.parse(fs.readFileSync(infoPath, 'utf8'));
      } catch {
        return null;
      }
    },

    /**
     * Publica um APK. Confere que é um APK do app (pacote certo) antes de substituir o atual.
     * Lança 'invalid_apk' ou 'wrong_package'.
     */
    publish(apk) {
      let apkInfo;
      try {
        apkInfo = readApkInfo(apk);
      } catch {
        throw new Error('invalid_apk');
      }
      if (apkInfo.packageName !== appPackage) throw new Error('wrong_package');

      const info = {
        ...apkInfo,
        size: apk.length,
        sha256: sha256(apk),
        publishedAt: Date.now(),
      };
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(`${apkPath}.tmp`, apk);
      fs.renameSync(`${apkPath}.tmp`, apkPath);
      fs.writeFileSync(`${infoPath}.tmp`, JSON.stringify(info, null, 2));
      fs.renameSync(`${infoPath}.tmp`, infoPath);
      return info;
    },
  };
}

module.exports = { createReleases };
