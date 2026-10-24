// 红果 App 官方接口取流（视频直链 + CENC 解密密钥）。
// 2026-10-23 变更：弃用无签名 multi_video_model/preload/v1（已被风控空响应），
// 改为与 APK 完全一致的签名链路：调用本地 Go 二进制 hongguo_sign 生成 X-Gorgon/X-Argus/X-Ladon，
// POST /novel/player/video_model/v1/，最高 1080p。
'use strict';

const { spawn } = require('child_process');
const path = require('path');

const BASE = 'https://api5-normal-sinfonlineb.fqnovel.com';
const UA = 'com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)';
// 与 APK HongguoSigner 完全一致的固定设备参数
const FIXED_DEVICE = {
  ac: 'wifi', aid: '8662', app_name: 'novelread', channel: 'update_64',
  device_brand: 'Redmi', device_id: '2720754845237044145', device_platform: 'android',
  device_type: '25053RT47C', dpi: '520', iid: '2684923656717301508', language: 'zh',
  manifest_version_code: '73532', os: 'android', os_api: '36', os_version: '16',
  resolution: '1280*2772', ssmix: 'a', update_version_code: '73532',
  version_code: '73532', version_name: '7.3.5.32',
};
const DEVICE_ID = FIXED_DEVICE.device_id;
const SIGNER_BIN = path.join(__dirname, 'hongguo_sign');

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

/** 调用本地 Go 签名器，返回 {gorgon, argusB64, ladonB64, nonce} */
function callSigner(rawQuery, bodyStr) {
  return new Promise((resolve, reject) => {
    const stamp = Math.floor(Date.now() / 1000);
    const child = spawn(SIGNER_BIN, [String(stamp), DEVICE_ID], { timeout: 10000 });
    const stdout = [], stderr = [];
    child.stdout.on('data', c => { if (stdout.length < 128) stdout.push(c); });
    child.stderr.on('data', c => stderr.push(c));
    child.on('error', reject);
    child.on('close', code => {
      if (code !== 0) return reject(new Error('签名器执行失败 exit=' + code + ': ' + Buffer.concat(stderr).toString('utf8').slice(0, 300)));
      const text = Buffer.concat(stdout).toString('utf8');
      const headers = {};
      for (const line of text.trim().split('\n')) {
        const eq = line.indexOf('=');
        if (eq < 0) continue;
        const k = line.slice(0, eq).toLowerCase();
        const v = line.slice(eq + 1);
        if (k === 'gorgon') headers['X-Gorgon'] = v;
        else if (k === 'argus') headers['X-Argus'] = Buffer.from(v, 'hex').toString('base64');
        else if (k === 'ladon') headers['X-Ladon'] = Buffer.from(v, 'hex').toString('base64');
        else if (k === 'nonce') headers['X-Khronos'] = v;
      }
      resolve(headers);
    });
    child.stdin.end(rawQuery + '\n' + bodyStr);
  });
}

/** 构造与 APK 一致的 query string（不编码 * 和 ~） */
function buildQuery() {
  const params = { ...FIXED_DEVICE, _rticket: String(Date.now()) };
  return Object.keys(params)
    .map(k => `${k}=${encodeURIComponent(params[k]).replace(/%2A/gi, '*').replace(/%7E/gi, '~')}`)
    .join('&');
}

/** 签名 POST：调用 Go 签名器生成 headers，POST 到 fqnovel */
async function signedPost(path, bodyObj) {
  const bodyStr = JSON.stringify(bodyObj);
  const rawQuery = buildQuery();
  const signHeaders = await callSigner(rawQuery, bodyStr);
  const url = `${BASE}${path}?${rawQuery}`;
  const headers = {
    ...signHeaders,
    'User-Agent': UA,
    'Content-Type': 'application/json',
    'Accept': 'application/json',
  };
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const mod = u.protocol === 'https:' ? require('https') : require('http');
    const req = mod.request(u, { method: 'POST', headers }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        if (res.statusCode !== 200) {
          return reject(new Error(`HTTP ${res.statusCode} len=${text.length}`));
        }
        if (!text.trim()) return reject(new Error('空响应（签名被风控）'));
        try { resolve(JSON.parse(text)); }
        catch { reject(new Error('响应解析失败: ' + text.slice(0, 200))); }
      });
    });
    req.on('error', reject);
    req.setTimeout(20000, () => req.destroy(new Error('请求超时')));
    req.write(bodyStr);
    req.end();
  });
}

/** 详情：拿全集 vid 列表（与下载集序对齐：vids[i] = 第 i+1 集）。 */
async function videoDetail(seriesId) {
  const json = await signedPost('/novel/player/video_detail/v1/', { series_id: String(seriesId) });
  const vd = (json.data || {}).video_data || {};
  const list = vd.video_list || [];
  if (!Array.isArray(list) || list.length === 0) throw new Error('红果详情未返回分集列表');
  const vids = list.map(v => (v && v.vid != null ? String(v.vid) : '')).filter(v => v);
  if (vids.length === 0) throw new Error('红果详情分集 vid 为空');
  return { vids, title: vd.series_title || '' };
}

/** 单集取流：签名调用 video_model/v1，选 ≤1080 最高档非 bytevc2。 */
async function videoModel(vid) {
  const json = await signedPost('/novel/player/video_model/v1/', {
    biz_param: { need_all_video_definition: true, video_platform: 3 },
    content_type: 1,
    video_id: String(vid),
  });
  const vmStr = (json.data || {}).video_model;
  if (!vmStr) throw new Error('无 video_model 字段');
  let vm;
  try { vm = typeof vmStr === 'string' ? JSON.parse(vmStr) : vmStr; }
  catch { throw new Error('video_model 解析失败'); }
  const variants = vm.video_list || [];
  if (!variants.length) throw new Error('video_list 为空');
  const collectUrls = o => {
    const out = [], seen = new Set();
    const add = value => {
      if (typeof value !== 'string') return;
      let a = String(value).trim();
      if (!/^https?:\/\//i.test(a)) {
        try { const d = Buffer.from(a, 'base64').toString('utf8').trim(); if (/^https?:\/\//i.test(d)) a = d; } catch { /* 非 base64 */ }
      }
      if (/^https?:\/\//i.test(a) && !seen.has(a)) { seen.add(a); out.push(a); }
    };
    for (const k of ['main_url', 'backup_url', 'backup_url_1', 'backup_url_2', 'backup_urls', 'url_list']) {
      const val = o[k];
      if (Array.isArray(val)) val.forEach(add); else add(val);
    }
    return out;
  };
  const rows = variants
    .map(o => ({ urls: collectUrls(o), meta: o.video_meta || {}, o }))
    .map(r => ({ ...r, q: qualityNumber(r.meta.definition || r.meta.vheight || '') }))
    .filter(r => r.urls.length > 0 && !/bytevc2|bvc2/i.test((r.meta.codec_type || '')))
    .sort((a, b) => b.q - a.q);
  if (rows.length === 0) throw new Error('没有可用清晰度');
  const selected = rows.find(r => r.q <= 1080) || rows[rows.length - 1];
  let key = '';
  const spade = selected.o.encrypt_info && selected.o.encrypt_info.spade_a;
  if (spade && String(spade).length > 0) key = deriveKey(String(spade));
  return { url: selected.urls[0], altUrls: selected.urls.slice(1), key, quality: selected.q > 0 ? `${selected.q}P` : '自动' };
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

/** 网页源保底：镜像站播放页 #videoInitialData → videoSrc（网页源直链）。返回 { url, referer }，referer 供下载时防盗链校验。 */
async function fetchWebUrl(id, episode) {
  for (const origin of MIRROR_ORIGINS) {
    try {
      const path = episode === 1 ? `/video/${id}/` : `/video/${id}/ep-${episode}/`;
      // 抓播放页带镜像站自身 Referer（部分镜像站校验同站来源）
      const { status, body } = await httpGet(origin + path, { Referer: origin + '/' });
      if (status !== 200) continue;
      const m = body.match(/<script[^>]*id="videoInitialData"[^>]*>([\s\S]*?)<\/script>/);
      if (!m) continue;
      const data = JSON.parse(m[1]);
      const src = data.videoSrc;
      if (typeof src === 'string' && /^https?:\/\//.test(src)) return { url: src, referer: origin };
    } catch { /* 换下一个 origin */ }
  }
  throw new Error('网页源播放地址获取失败');
}

module.exports = { videoDetail, videoModel, deriveKey, searchSeries, fetchWebUrl };
