'use strict';
/**
 * 红果官方签名（guoapp 对齐，python 已验证实现 1:1 移植）。
 * 生成 X-Gorgon / X-Argus / X-Ladon / X-Khronos / X-SS-STUB。
 */
const crypto = require('crypto');

function uvarint(value) {
  const out = [];
  let v = BigInt(value);
  while (true) {
    let b = Number(v & 0x7fn);
    v >>= 7n;
    if (v !== 0n) { out.push(b | 0x80); } else { out.push(b); break; }
  }
  return out;
}

const SM3_IV = [0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600, 0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e];
const P = (x, n) => ((x >>> (32 - n)) | (x << n)) >>> 0;
function sm3(data) {
  const msg = Buffer.from(data);
  const len = msg.length;
  const padded = Buffer.alloc((Math.floor((len + 8) / 64) + 1) * 64);
  msg.copy(padded);
  padded[len] = 0x80;
  const bitLen = BigInt(len) * 8n;
  padded.writeBigUInt64BE(bitLen, padded.length - 8);
  let state = SM3_IV.slice();
  for (let off = 0; off < padded.length; off += 64) {
    const words = new Array(68);
    for (let i = 0; i < 16; i++) words[i] = padded.readUInt32BE(off + i * 4);
    for (let i = 16; i < 68; i++) {
      const v = words[i - 16] ^ words[i - 9] ^ P(words[i - 3], 15);
      words[i] = (v ^ P(v, 15) ^ P(v, 23) ^ P(words[i - 13], 7) ^ words[i - 6]) >>> 0;
    }
    let [a, b, c, d, e, f, g, h] = state;
    for (let i = 0; i < 64; i++) {
      let t = 0x79cc4519, ff = a ^ b ^ c, gg = e ^ f ^ g;
      if (i >= 16) { t = 0x7a879d8a; ff = (a & b) | (a & c) | (b & c); gg = (e & f) | (~e & g); }
      const ss1 = P((P(a, 12) + e + P(t, i)) >>> 0, 7) >>> 0;
      const tt1 = (ff + d + (ss1 ^ P(a, 12)) + (words[i] ^ words[i + 4])) >>> 0;
      const tt2 = (gg + h + ss1 + words[i]) >>> 0;
      d = c; c = P(b, 9) >>> 0; b = a; a = tt1 >>> 0;
      h = g; g = P(f, 19) >>> 0; f = e;
      e = (tt2 ^ P(tt2, 9) ^ P(tt2, 17)) >>> 0;
    }
    state = state.map((s, i) => (s ^ [a, b, c, d, e, f, g, h][i]) >>> 0);
  }
  const out = Buffer.alloc(32);
  state.forEach((s, i) => out.writeUInt32BE(s, i * 4));
  return out;
}

function rotl64(v, n) {
  if (n >= 0n) {
    const s = Number(n);
    return BigInt.asUintN(64, (v << BigInt(s)) | (v >> BigInt(64 - s)));
  }
  const s = Number(-n);
  return BigInt.asUintN(64, (v >> BigInt(s)) | (v << BigInt(64 - s)));
}

const ARGUS_KEY = Buffer.from('ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c', 'hex');
function hongguoArgus(query, body, timestamp, deviceID, nonce) {
  const varint = (msg, field, value) => Buffer.concat([msg, Buffer.from(uvarint(field * 8)), Buffer.from(uvarint(value))]);
  const bytesField = (msg, field, value) => Buffer.concat([msg, Buffer.from(uvarint(field * 8 + 2)), Buffer.from(uvarint(value.length)), Buffer.from(value)]);

  let bodyHash = sm3(Buffer.alloc(16));
  if (body && body.length > 0) {
    const stub = crypto.createHash('md5').update(body).digest();
    bodyHash = sm3(stub);
  }
  const queryHash = sm3(Buffer.from(query, 'utf8'));
  const counters = varint(varint(varint(varint(Buffer.alloc(0), 1, 1), 2, 1), 3, 1), 7, 3348294860);
  let environment = bytesField(Buffer.alloc(0), 1, Buffer.from('25053RT47C'));
  environment = bytesField(environment, 2, Buffer.from('16'));
  environment = bytesField(environment, 3, Buffer.from('update_64'));
  environment = varint(environment, 4, 2147483648);

  let message = varint(Buffer.alloc(0), 1, 0x20200929 * 2);
  message = varint(message, 2, 2);
  if (nonce.length >= 10) {
    const n = nonce.readUInt32LE(6) & 0x7fffffff;
    message = varint(message, 3, n);
  }
  message = bytesField(message, 4, Buffer.from('8662'));
  message = bytesField(message, 5, Buffer.from(String(deviceID), 'utf8'));
  message = bytesField(message, 6, Buffer.from('1611921764'));
  message = bytesField(message, 7, Buffer.from('7.3.5.32'));
  message = bytesField(message, 8, Buffer.from('v04.07.01-ml-android'));
  message = varint(message, 9, 135135744);
  message = bytesField(message, 10, Buffer.alloc(8));
  message = varint(message, 11, 0);
  message = varint(message, 12, timestamp * 2);
  message = bytesField(message, 13, bodyHash.subarray(0, 6));
  message = bytesField(message, 14, queryHash.subarray(0, 6));
  message = bytesField(message, 15, counters);
  message = bytesField(message, 16, Buffer.alloc(0));
  message = bytesField(message, 20, Buffer.from('none'));
  message = varint(message, 21, 738);
  message = bytesField(message, 23, environment);
  message = varint(message, 25, 2);

  let data = pad16(message);
  const seed = Buffer.concat([ARGUS_KEY, Buffer.from([0x3c, 0xcc, 0x56, 0x7b]), ARGUS_KEY]);
  const derived = sm3(seed);
  const rounds = [];
  for (let i = 0; i < 4; i++) rounds.push(derived.readBigUInt64LE(i * 8));
  for (let i = 4; i < 72; i++) {
    const mixed = rotl64(rounds[i - 1], -3n) ^ rounds[i - 3];
    const mixed2 = mixed ^ rotl64(mixed, -1n);
    const bit = (0x3dc94c3a046d678bn >> BigInt((i - 4) % 62)) & 1n;
    rounds.push(BigInt.asUintN(64, (~rounds[i - 4]) ^ mixed2 ^ bit ^ 3n));
  }
  for (let off = 0; off < data.length; off += 16) {
    let left = data.readBigUInt64LE(off);
    let right = data.readBigUInt64LE(off + 8);
    for (const rnd of rounds) {
      const nl = right;
      const nr = BigInt.asUintN(64, left ^ (rotl64(right, 1n) & rotl64(right, 8n)) ^ rotl64(right, 2n) ^ rnd);
      left = nl; right = nr;
    }
    data.writeBigUInt64LE(left, off);
    data.writeBigUInt64LE(right, off + 8);
  }
  const xor = Buffer.from([0xd0, 0x4f, 0xfd, 0xff, 0xd0, 0x4f, 0xfd, 0xff]);
  for (let i = 0; i < data.length; i++) data[i] ^= xor[i % 8];
  data = Buffer.concat([xor, data]);
  data = Buffer.from(data.reverse());
  let container = Buffer.concat([Buffer.from([0xa6, 0xe7, 0x83, 0xee, 0x70, 0x01, 0x10, 0x09, 0x18]), data, Buffer.from([0x56, 0x7b])]);
  container = pad16(container);
  const aesKey = crypto.createHash('md5').update(ARGUS_KEY.subarray(0, 16)).digest();
  const aesIv = crypto.createHash('md5').update(ARGUS_KEY.subarray(16)).digest();
  const cipher = crypto.createCipheriv('aes-128-cbc', aesKey, aesIv);
  cipher.setAutoPadding(false); // 对齐 Go/python：不自动 PKCS7 padding
  container = Buffer.concat([cipher.update(container), cipher.final()]);
  return Buffer.concat([Buffer.from([0x3c, 0xcc]), container]).toString('base64');
}

function hongguoLadon(aid, timestamp, prefix) {
  const hash = crypto.createHash('md5').update(Buffer.concat([Buffer.from(prefix), Buffer.from(aid, 'utf8')])).digest();
  const digest = Buffer.from(hash.toString('hex'), 'ascii');
  let left = digest.readBigUInt64LE(0);
  let right = digest.readBigUInt64LE(8);
  const queue = [digest.readBigUInt64LE(16), digest.readBigUInt64LE(24)];
  const keys = [];
  for (let i = 0; i < 34; i++) {
    keys.push(left);
    const mixed = BigInt.asUintN(64, (rotl64(right, -8n) + left) ^ BigInt(i));
    queue.push(mixed);
    left = mixed ^ rotl64(left, 3n);
    right = queue.shift();
  }
  let data = pad16(Buffer.from(`${timestamp}-1611921764-${aid}`, 'ascii'));
  for (let off = 0; off < data.length; off += 16) {
    let lv = data.readBigUInt64LE(off);
    let rv = data.readBigUInt64LE(off + 8);
    for (const k of keys) {
      rv = BigInt.asUintN(64, k ^ (lv + rotl64(rv, -8n)));
      lv = rv ^ rotl64(lv, 3n);
    }
    data.writeBigUInt64LE(lv, off);
    data.writeBigUInt64LE(rv, off + 8);
  }
  return Buffer.concat([Buffer.from(prefix), data]).toString('base64');
}

function pad16(data) {
  const p = 16 - (data.length % 16);
  return Buffer.concat([data, Buffer.alloc(p, p)]);
}

function rotl8(v, n) { return ((v << n) | (v >>> (8 - n))) & 0xff; }
function rev8(v) {
  let r = 0;
  for (let i = 0; i < 8; i++) { r = (r << 1) | (v & 1); v >>>= 1; }
  return r & 0xff;
}

function signPlayerRequest(rawQuery, body, ts, deviceId) {
  const bodyBuf = Buffer.isBuffer(body) ? body : Buffer.from(body, 'utf8');
  const queryHash = crypto.createHash('md5').update(rawQuery, 'utf8').digest();
  const payload = Buffer.alloc(20);
  queryHash.copy(payload, 0, 0, 4);
  let stub = null;
  if (bodyBuf.length > 0) {
    const bodyHash = crypto.createHash('md5').update(bodyBuf).digest();
    bodyHash.copy(payload, 4, 0, 4);
    stub = bodyHash.toString('hex').toUpperCase();
  }
  payload[12] = 0; payload[13] = 6; payload[14] = 11; payload[15] = 28;
  payload.writeUInt32BE(ts, 16);
  const signKey = Buffer.from([0x44, 0xb9, 0xb9, 0xd9, 0xa4, 0xae, 0xf9, 0xfc, 0xa4, 0x93, 0xaa, 0x75, 0x7c, 0xa3, 0xc2, 0xc4, 0xa4, 0x96, 0x93, 0x8f]);
  for (let i = 0; i < payload.length; i++) payload[i] ^= signKey[i];
  for (let i = 0; i < payload.length; i++) {
    payload[i] = (rev8(rotl8(payload[i], 4) ^ payload[(i + 1) % payload.length]) ^ 0xff ^ payload.length) & 0xff;
  }
  const gorgon = Buffer.concat([Buffer.from([0x84, 0x04, 0x40, 0x1c, 0, 0]), payload]);
  const nonce = crypto.randomBytes(11);
  const headers = {
    'X-Khronos': String(ts),
    'X-Gorgon': gorgon.toString('hex').toUpperCase(),
    'X-SS-Req-Ticket': String(Date.now()),
    'X-Argus': hongguoArgus(rawQuery, bodyBuf, ts, deviceId, nonce),
    'X-Ladon': hongguoLadon('8662', ts, nonce.subarray(2, 6)),
  };
  if (stub) headers['X-SS-STUB'] = stub;
  return headers;
}

module.exports = { signPlayerRequest, sm3, hongguoArgus, hongguoLadon, uvarint };
