'use strict';

const fs = require('node:fs');
const path = require('node:path');

/**
 * Persistência simples em um arquivo JSON.
 * Guarda apenas hashes de senhas, segredos e tokens, nunca os valores originais.
 */
class Store {
  constructor(file) {
    this.file = file;
    this.data = { users: {}, sessions: {}, devices: {} };
    try {
      const saved = JSON.parse(fs.readFileSync(file, 'utf8'));
      this.data = {
        users: saved.users || {},
        sessions: saved.sessions || {},
        devices: saved.devices || {},
      };
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
    }
  }

  save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
    fs.renameSync(tmp, this.file);
  }
}

module.exports = { Store };
