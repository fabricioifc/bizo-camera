'use strict';

const zlib = require('node:zlib');

/**
 * Lê o pacote e a versão de um APK (um arquivo zip) sem ferramentas do Android:
 * abre o AndroidManifest.xml compilado (XML binário) e pega os atributos do <manifest>.
 */

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_SIGNATURE = 0x02014b50;
const LOCAL_SIGNATURE = 0x04034b50;

// Tipos de bloco do XML binário do Android.
const RES_XML_TYPE = 0x0003;
const RES_STRING_POOL_TYPE = 0x0001;
const RES_XML_START_ELEMENT_TYPE = 0x0102;
const UTF8_FLAG = 0x100;
const TYPE_STRING = 0x03;
const TYPE_INT_DEC = 0x10;
const TYPE_INT_HEX = 0x11;
const NO_ENTRY = 0xffffffff;

/** Conteúdo (descompactado) de um arquivo dentro do zip, ou null se não existir. */
function readZipEntry(zip, wanted) {
  // Fim do diretório central: nos últimos 64 KB + 22 bytes do arquivo.
  let eocd = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 65_557); i--) {
    if (zip.readUInt32LE(i) === EOCD_SIGNATURE) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('not_a_zip');
  const count = zip.readUInt16LE(eocd + 10);
  let offset = zip.readUInt32LE(eocd + 16);

  for (let i = 0; i < count; i++) {
    if (zip.readUInt32LE(offset) !== CENTRAL_SIGNATURE) throw new Error('bad_zip');
    const method = zip.readUInt16LE(offset + 10);
    const compressedSize = zip.readUInt32LE(offset + 20);
    const nameLength = zip.readUInt16LE(offset + 28);
    const extraLength = zip.readUInt16LE(offset + 30);
    const commentLength = zip.readUInt16LE(offset + 32);
    const localOffset = zip.readUInt32LE(offset + 42);
    const name = zip.toString('utf8', offset + 46, offset + 46 + nameLength);
    offset += 46 + nameLength + extraLength + commentLength;
    if (name !== wanted) continue;

    if (zip.readUInt32LE(localOffset) !== LOCAL_SIGNATURE) throw new Error('bad_zip');
    const start = localOffset + 30 + zip.readUInt16LE(localOffset + 26) + zip.readUInt16LE(localOffset + 28);
    const data = zip.subarray(start, start + compressedSize);
    if (method === 0) return data;
    if (method === 8) return zlib.inflateRawSync(data);
    throw new Error('unsupported_compression');
  }
  return null;
}

// Tabela de textos do XML binário (UTF-8 ou UTF-16).
function readStringPool(xml, chunk) {
  const count = xml.readUInt32LE(chunk + 8);
  const flags = xml.readUInt32LE(chunk + 16);
  const stringsStart = chunk + xml.readUInt32LE(chunk + 20);
  const offsets = chunk + xml.readUInt16LE(chunk + 2);
  const utf8 = (flags & UTF8_FLAG) !== 0;
  const strings = [];
  for (let i = 0; i < count; i++) {
    let at = stringsStart + xml.readUInt32LE(offsets + i * 4);
    if (utf8) {
      // Dois tamanhos (caracteres e bytes), cada um com 1 ou 2 bytes.
      at += xml[at] & 0x80 ? 2 : 1;
      let length = xml[at];
      if (length & 0x80) {
        length = ((length & 0x7f) << 8) | xml[at + 1];
        at += 2;
      } else {
        at += 1;
      }
      strings.push(xml.toString('utf8', at, at + length));
    } else {
      let length = xml.readUInt16LE(at);
      at += 2;
      if (length & 0x8000) {
        length = ((length & 0x7fff) << 16) | xml.readUInt16LE(at);
        at += 2;
      }
      strings.push(xml.toString('utf16le', at, at + length * 2));
    }
  }
  return strings;
}

/** Atributos do primeiro elemento (<manifest>) do AndroidManifest.xml binário. */
function readManifestAttributes(xml) {
  if (xml.readUInt16LE(0) !== RES_XML_TYPE) throw new Error('bad_manifest');
  let strings = [];
  let chunk = xml.readUInt16LE(2);
  while (chunk + 8 <= xml.length) {
    const type = xml.readUInt16LE(chunk);
    const size = xml.readUInt32LE(chunk + 4);
    if (size < 8) throw new Error('bad_manifest');
    if (type === RES_STRING_POOL_TYPE) {
      strings = readStringPool(xml, chunk);
    } else if (type === RES_XML_START_ELEMENT_TYPE) {
      const attributeStart = xml.readUInt16LE(chunk + 24);
      const attributeSize = xml.readUInt16LE(chunk + 26);
      const attributeCount = xml.readUInt16LE(chunk + 28);
      const attributes = {};
      for (let i = 0; i < attributeCount; i++) {
        const at = chunk + 16 + attributeStart + i * attributeSize;
        const name = strings[xml.readUInt32LE(at + 4)];
        const raw = xml.readUInt32LE(at + 8);
        const dataType = xml[at + 15];
        const data = xml.readUInt32LE(at + 16);
        if (dataType === TYPE_STRING) attributes[name] = strings[data];
        else if (raw !== NO_ENTRY) attributes[name] = strings[raw];
        else if (dataType === TYPE_INT_DEC || dataType === TYPE_INT_HEX) attributes[name] = data;
      }
      return attributes;
    }
    chunk += size;
  }
  throw new Error('bad_manifest');
}

/** { packageName, versionCode, versionName } de um APK. Lança erro se não for um APK válido. */
function readApkInfo(apk) {
  const manifest = readZipEntry(apk, 'AndroidManifest.xml');
  if (!manifest) throw new Error('not_an_apk');
  const attributes = readManifestAttributes(manifest);
  const versionCode = Number(attributes.versionCode);
  if (typeof attributes.package !== 'string' || !Number.isInteger(versionCode) || versionCode <= 0) {
    throw new Error('not_an_apk');
  }
  return {
    packageName: attributes.package,
    versionCode,
    versionName: typeof attributes.versionName === 'string' ? attributes.versionName : String(versionCode),
  };
}

module.exports = { readApkInfo, readZipEntry };
