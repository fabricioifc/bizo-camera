'use strict';

// Monta um APK mínimo para os testes: um zip com só o AndroidManifest.xml em XML binário.

const zlib = require('node:zlib');

function u16(value) {
  const buf = Buffer.alloc(2);
  buf.writeUInt16LE(value);
  return buf;
}

function u32(value) {
  const buf = Buffer.alloc(4);
  buf.writeUInt32LE(value >>> 0);
  return buf;
}

function stringPool(strings) {
  const data = Buffer.concat(strings.map((text) => Buffer.concat([u16(text.length), Buffer.from(text, 'utf16le'), u16(0)])));
  const padded = Buffer.concat([data, Buffer.alloc((4 - (data.length % 4)) % 4)]);
  const offsets = [];
  let at = 0;
  for (const text of strings) {
    offsets.push(u32(at));
    at += 2 + text.length * 2 + 2;
  }
  const headerSize = 28;
  const stringsStart = headerSize + strings.length * 4;
  const size = stringsStart + padded.length;
  return Buffer.concat([
    u16(0x0001), u16(headerSize), u32(size), u32(strings.length), u32(0), u32(0), u32(stringsStart), u32(0),
    ...offsets, padded,
  ]);
}

function attribute(name, raw, type, data) {
  return Buffer.concat([u32(0xffffffff), u32(name), u32(raw), u16(8), Buffer.from([0, type]), u32(data)]);
}

/** AndroidManifest.xml binário com <manifest package versionCode versionName>. */
function manifest({ packageName, versionCode, versionName }) {
  const strings = ['versionCode', 'versionName', 'package', 'manifest', packageName, versionName];
  const attributes = [
    attribute(0, 0xffffffff, 0x10, versionCode),
    attribute(1, 5, 0x03, 5),
    attribute(2, 4, 0x03, 4),
  ];
  const element = Buffer.concat([
    u32(0xffffffff), u32(3), u16(20), u16(20), u16(attributes.length), u16(0), u16(0), u16(0), ...attributes,
  ]);
  const start = Buffer.concat([u16(0x0102), u16(16), u32(16 + element.length), u32(1), u32(0xffffffff), element]);
  const pool = stringPool(strings);
  const body = Buffer.concat([pool, start]);
  return Buffer.concat([u16(0x0003), u16(8), u32(8 + body.length), body]);
}

/** Zip com um arquivo comprimido (deflate). */
function zip(name, content) {
  const data = zlib.deflateRawSync(content);
  const nameBytes = Buffer.from(name);
  const local = Buffer.concat([
    u32(0x04034b50), u16(20), u16(0), u16(8), u16(0), u16(0), u32(0),
    u32(data.length), u32(content.length), u16(nameBytes.length), u16(0), nameBytes,
  ]);
  const central = Buffer.concat([
    u32(0x02014b50), u16(20), u16(20), u16(0), u16(8), u16(0), u16(0), u32(0),
    u32(data.length), u32(content.length), u16(nameBytes.length), u16(0), u16(0), u16(0), u16(0), u32(0), u32(0),
    nameBytes,
  ]);
  const centralOffset = local.length + data.length;
  const end = Buffer.concat([
    u32(0x06054b50), u16(0), u16(0), u16(1), u16(1), u32(central.length), u32(centralOffset), u16(0),
  ]);
  return Buffer.concat([local, data, central, end]);
}

function makeApk({ packageName = 'br.dev.bizo.cameramonitor', versionCode = 42, versionName = '1.2.3' } = {}) {
  return zip('AndroidManifest.xml', manifest({ packageName, versionCode, versionName }));
}

module.exports = { makeApk };
