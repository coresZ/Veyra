// 红果 App 官方接口取流（视频直链 + CENC 解密密钥）。
// 与 third_party/hongguo GuoSigner + MediaCrypto 1:1 移植：
//   - 完整 query（aid/version_code/device_id/iid…）+ X-Gorgon/X-SS-STUB/X-Khronos 签名
//   - 每次请求随机 device_id/iid（19 位），NAS 家宽出口 + 低频分散 = 低风控
//   - video_detail/v1 拿全集 vid 列表；video_model/v1 单集取流，多清晰度选 ≤1080 最高档
//   - spade_a → deriveKey → CENC 密钥（ffmpeg -decryption_key 原生解密）
'use strict';

const crypto = require('crypto');

const BASE = 'https://api5-normal-sinfonlineb.fqnovel.com';
const UA = 'com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)';
const KEY = Buffer.from([
  0x44, 0xb9, 0xb9, 0xd9, 0xa4, 0xae, 0xf9, 0xfc, 0xa4, 0x93, 0xaa, 0x75, 0x7c, 0xa3, 0xc2, 0xc4, 0xa4, 0x96, 0x93, 0x8f,
]);

const QUALITY = {
  2160: 2160, 1440: 1440, 1080: 1080, 720: 720, 576: 576, 540: 540, 480: 480, 360: 360,
  1920: 1080, 1280: 720, 1024: 576, 854: 480, 640: 360,
};

function qualityNumber(value) {
  const m = /2160|1440|1080|720|576|540|480|360/.exec(String(value || ''));
  if (m) return parseInt(m[0], 10);
  if (QUALITY[String(value)]) return QUALITY[String(value)];
  const n = parseInt(value, 10);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

function newDeviceId() {
  return String(1_000_000_000_000_000_000n + (BigInt(Math.floor(Math.random() * 8_000_000_000)) * 1_000_000_000n + BigInt(Math.floor(Math.random() * 1_000_000_000))));
}

function rotl8(x, n) { return ((x << n) | (x >>> (8 - n))) & 0xff; }
function rev8(b) {
  let x = b, r = 0;
  for (let i = 0; i < 8; i++) { r = (r << 1) | (x & 1); x >>>= 1; }
  return r;
}

/** 构造与红果 App 一致的全参数签名请求。path 如 /novel/player/video_model/v1/ */
function sign(path, body) {
  const deviceId = newDeviceId();
  const params = {
    aid: '8662', app_name: 'novelread', version_code: '73532', version_name: '7.3.5.32',
    manifest_version_code: '73532', update_version_code: '73532', channel: 'update_64',
    device_platform: 'android', os: 'android', ssmix: 'a', device_type: '25053RT47C',
    device_brand: 'Redmi', language: 'zh', os_api: '36', os_version: '16',
    resolution: '1280*2772', dpi: '520', ac: 'wifi', device_id: deviceId, iid: newDeviceId(),
  };
  // 与 Kotlin qenc 一致：`*` 不编码（Java URLEncoder 会编成 %2A，红果按未编码 query 签名校验）
  const qs = Object.keys(params).map(k => `${k}=${encodeURIComponent(params[k]).replace(/%2A/gi, '*').replace(/%7E/g, '~')}`).join('&');
  const url = `${BASE}${path}?${qs}`;
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.alloc(20);
  crypto.createHash('md5').update(qs, 'utf8').digest().copy(payload, 0, 0, 4);
  let stub = null;
  if (body) {
    const bh = crypto.createHash('md5').update(body).digest();
    bh.copy(payload, 4, 0, 4);
    stub = bh.toString('hex').toUpperCase();
  }
  payload[12] = 0; payload[13] = 6; payload[14] = 11; payload[15] = 28;
  payload.writeUInt32BE(now, 16);
  for (let i = 0; i < 20; i++) payload[i] ^= KEY[i];
  for (let i = 0; i < 20; i++) {
    payload[i] = (rev8(rotl8(payload[i], 4) ^ payload[(i + 1) % 20]) ^ 0xff ^ 20) & 0xff;
  }
  const gorgon = Buffer.concat([Buffer.from([0x84, 0x04, 0x40, 0x1c, 0x00, 0x00]), payload]);
  const headers = {
    'User-Agent': UA, Accept: 'application/json', 'X-XS-From-Web': '0', 'Sdk-Version': '2',
    'X-Gorgon': gorgon.toString('hex'), 'X-Khronos': String(now),
    'X-SS-Req-Ticket': String(Date.now()),
  };
  if (stub) headers['X-SS-STUB'] = stub;
  return { url, headers };
}

function request(path, bodyBytes) {
  const { url, headers } = sign(path, bodyBytes);
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const mod = u.protocol === 'https:' ? require('https') : require('http');
    const req = mod.request(u, { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json; charset=utf-8' } }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        try {
          const json = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (json.Code !== undefined && json.Code !== 0 && json.Code !== '0') {
            return reject(new Error(`红果接口错误 Code=${json.Code} ${json.msg || json.message || ''}`.trim()));
          }
          if (json.code !== undefined && json.code !== 0 && json.code !== '0' && json.code !== 101000) {
            // 101000 服务异常在取流时由调用方降级；这里照常返回让上层判定
          }
          resolve(json);
        } catch (e) { reject(new Error('红果接口响应解析失败')); }
      });
    });
    req.on('error', reject);
    req.setTimeout(20000, () => { req.destroy(new Error('红果接口超时')); });
    req.end(bodyBytes);
  });
}

/** 详情：拿全集 vid 列表（与下载集序对齐：vids[i] = 第 i+1 集）。 */
async function videoDetail(seriesId) {
  const body = Buffer.from(JSON.stringify({ series_id: String(seriesId) }), 'utf8');
  const json = await request('/novel/player/video_detail/v1/', body);
  const vd = (json.data || {}).video_data || {};
  const list = vd.video_list || [];
  if (!Array.isArray(list) || list.length === 0) throw new Error('红果详情未返回分集列表');
  const vids = list.map(v => (v && v.vid != null ? String(v.vid) : '')).filter(v => v);
  if (vids.length === 0) throw new Error('红果详情分集 vid 为空');
  return { vids, title: vd.series_title || '' };
}

/** 单集取流：多清晰度选 ≤1080 最高档（与 Kotlin stream(maxQuality=1080) 一致），返回 { url, key }。 */
async function videoModel(vid) {
  const body = Buffer.from(JSON.stringify({
    video_id: String(vid), content_type: 1,
    biz_param: { need_all_video_definition: true, video_platform: 3 },
  }), 'utf8');
  const json = await request('/novel/player/video_model/v1/', body);
  if (json.Code !== undefined && json.Code !== 0 && json.Code !== '0') throw new Error(`取流拒绝 Code=${json.Code}`);
  let model = (json.data || {}).video_model || {};
  // 红果返回的 video_model 是 JSON 字符串（内部才是多清晰度列表），需先 parse
  if (typeof model === 'string') {
    try { model = JSON.parse(model); } catch { model = {}; }
  }
  const rawList = model.video_list;
  const variants = Array.isArray(rawList) ? rawList : Object.values(rawList || {});
  // ByteVC2 资源在 Android extractor 只有音频没视频轨，绝不选；只选有直链的清晰度
  const rows = variants
    .filter(o => o && String(o.main_url || '').length > 0 && !/bytevc2|bvc2/i.test(((o.video_meta || {}).codec_type) || ''))
    .map(o => {
      const meta = o.video_meta || {};
      const label = meta.definition || meta.vheight || '';
      return { q: qualityNumber(label), url: cleanUrl(o.main_url), meta, o };
    })
    .filter(r => r.url.startsWith('https://') || r.url.startsWith('http://'))
    .sort((a, b) => b.q - a.q);
  if (rows.length === 0) throw new Error('没有可用清晰度');
  const selected = rows.find(r => r.q <= 1080) || rows[rows.length - 1];
  let key = '';
  const spade = selected.o.encrypt_info && selected.o.encrypt_info.spade_a;
  if (spade && String(spade).length > 0) key = deriveKey(String(spade));
  return { url: selected.url, key, quality: selected.q > 0 ? `${selected.q}P` : '自动' };
}

function cleanUrl(raw) {
  if (!raw) return '';
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(raw)) return raw;
  return raw;
}

/** spade_a → CENC 解密密钥（Kotlin MediaCrypto.deriveKey 1:1 移植）。 */
function deriveKey(value) {
  const raw = Buffer.from(value, 'base64');
  if (raw.length < 3) throw new Error('内容密钥格式异常');
  const length = Math.min(raw.length - ((raw[0] ^ raw[1] ^ raw[2]) & 0xff) + 47, raw.length - 1);
  if (length < 33) throw new Error('内容密钥长度异常');
  const work = Buffer.from(raw.subarray(1, 1 + length));
  let a = 85, b = 246;
  for (let i = 0; i < work.length; i++) {
    const old = work[i] & 0xff;
    const previous = (i & 1) !== 0 ? a : b;
    if ((i & 1) !== 0) a = old; else b = old;
    const bitCount = popcount(i);
    work[i] = (-21 - bitCount + (previous ^ old)) & 0xff;
  }
  const key = Buffer.from(work.subarray(1, 33).toString('ascii'), 'hex');
  if (key.length !== 16) throw new Error('内容密钥长度异常');
  return key.toString('hex');
}

function popcount(n) {
  let c = 0;
  while (n) { n &= n - 1; c++; }
  return c;
}

// ===== NAS 自主系列解析（手机端只推 seriesId/title/集数，不再解析 URL）=====

const MIRROR_ORIGINS = [
  'https://thu.pqhybuxo.cc',
  'https://www.pqhybuxo.cc',
  'https://m.pqhybuxo.cc',
];

function httpGet(url, headers) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const mod = u.protocol === 'https:' ? require('https') : require('http');
    const req = mod.get(u, { headers: Object.assign({ 'User-Agent': 'Mozilla/5.0' }, headers || {}) }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        return resolve(httpGet(res.headers.location, headers));
      }
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('error', reject);
    req.setTimeout(15000, () => req.destroy(new Error('请求超时')));
  });
}

/** 从 hongguoduanju.com 搜索结果匹配红果 series_id（无需红果签名；标题一致或包含且集数满足）。 */
async function searchSeries(title, expectEpisodes) {
  const q = encodeURIComponent(String(title || '').trim());
  if (!q) throw new Error('剧名缺失');
  const { body } = await httpGet('https://hongguoduanju.com/search/' + q);
  const start = body.indexOf('_ROUTER_DATA = ');
  if (start < 0) throw new Error('搜索页结构异常');
  const jsonStart = body.indexOf('{', start);
  if (jsonStart < 0) throw new Error('搜索页数据缺失');
  // JSON 内文本可能含 "</script>"，不能用第一个 </script> 截断；做括号平衡解析
  let depth = 0, inStr = false, esc = false, jsonEnd = -1;
  for (let i = jsonStart; i < body.length; i++) {
    const ch = body[i];
    if (esc) { esc = false; continue; }
    if (inStr) {
      if (ch === '\\') esc = true;
      else if (ch === '"') inStr = false;
      continue;
    }
    if (ch === '"') inStr = true;
    else if (ch === '{') depth++;
    else if (ch === '}') { depth--; if (depth === 0) { jsonEnd = i + 1; break; } }
  }
  if (jsonEnd < 0) throw new Error('搜索页数据不完整');
  const data = JSON.parse(body.slice(jsonStart, jsonEnd));
  const ld = data.loaderData || {};
  const norm = s => String(s || '').replace(/\s+/g, '').toLowerCase();
  const titleNorm = norm(title);
  const want = Number(expectEpisodes) || 0;
  let best = null;
  for (const key of Object.keys(ld)) {
    const section = ld[key];
    if (!section || !Array.isArray(section.searchList)) continue;
    for (const row of section.searchList) {
      const vd = (row && row.video_data) || {};
      const sid = String(vd.series_id || '');
      if (!/^[0-9]{1,30}$/.test(sid)) continue;
      const st = norm(vd.series_title || '');
      const eps = parseInt(String(vd.episode_right_text || '').replace(/[^0-9]/g, ''), 10) || 0;
      if (st === titleNorm) return sid; // 标题完全一致最优
      if (!best && st && (st.includes(titleNorm) || titleNorm.includes(st)) && (!want || eps >= want)) best = sid;
    }
  }
  if (best) return best;
  throw new Error('未匹配到对应红果剧集');
}

/** 网页源保底：镜像站播放页 #videoInitialData → videoSrc（网页源直链）。 */
async function fetchWebUrl(id, episode) {
  for (const origin of MIRROR_ORIGINS) {
    try {
      const path = episode === 1 ? `/video/${id}/` : `/video/${id}/ep-${episode}/`;
      const { status, body } = await httpGet(origin + path);
      if (status !== 200) continue;
      const m = body.match(/<script[^>]*id="videoInitialData"[^>]*>([\s\S]*?)<\/script>/);
      if (!m) continue;
      const data = JSON.parse(m[1]);
      const src = data.videoSrc;
      if (typeof src === 'string' && /^https?:\/\//.test(src)) return src;
    } catch { /* 换下一个 origin */ }
  }
  throw new Error('网页源播放地址获取失败');
}

module.exports = { videoDetail, videoModel, deriveKey, sign, newDeviceId, searchSeries, fetchWebUrl };
