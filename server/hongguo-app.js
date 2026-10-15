// 红果 App 官方接口取流（视频直链 + CENC 解密密钥）。
// 2026-10-15 变更：video_model/v1 + 签名接口已被红果对第三方静默风控（HTTP 200 空 body）。
// 改用 multi_video_model/preload/v1（无签名 + 固定设备参数，与 hongguo-dl 同款，实测正常）：
//   - 固定设备 query（klink_egdi/iid/device_id/cdid…，对齐 hongguo-dl 客户端固定设备），无 X-Gorgon 等签名
//   - video_detail/v1 拿全集 vid 列表；multi_video_model/preload/v1 单集取流，多清晰度选 ≤1080 最高档
//   - spade_a → deriveKey → CENC 密钥（ffmpeg -decryption_key 原生解密）
'use strict';

const BASE = 'https://api5-normal-sinfonlineb.fqnovel.com';
const UA = 'com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)';
// 固定设备参数（hongguo-dl 同款；无签名接口依赖这些参数取流）
const COMMON_QUERY = {
  klink_egdi: 'AAI29o4dI-eMiO73_SRSbZ_0By1v3fUSriNeu8-L951MoXhWT88pzj5B',
  iid: '3788260546453235', device_id: '538083340353620', ac: 'wifi',
  channel: 'oppo_8662_64', aid: '8662', app_name: 'novelread',
  version_code: '71532', version_name: '7.1.5.32', device_platform: 'android',
  os: 'android', ssmix: 'a', device_type: 'SM-N9860', device_brand: 'Samsung',
  language: 'zh', os_api: '28', os_version: '9', manifest_version_code: '71532',
  resolution: '900*1600', dpi: '320', update_version_code: '71532',
  host_abi: 'arm64-v8a', dragon_device_type: 'pad', pv_player: '71532',
  compliance_status: '0', need_personal_recommend: '1', player_so_load: '1',
  is_android_pad_screen: '0', rom_version: 'PQ3A.190705.10241111+release-keys',
  cdid: 'b4f93387-5319-4134-aab9-2cd4e9279b8f',
};

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

/** 无签名 POST：固定设备 query + _rticket。path 如 /novel/player/multi_video_model/preload/v1 */
function request(path, bodyBytes) {
  const params = { ...COMMON_QUERY, _rticket: String(Date.now()) };
  // 与 Kotlin qenc 一致：`*` 不编码（Java URLEncoder 会编成 %2A，红果按未编码 query 校验）
  const qs = Object.keys(params).map(k => `${k}=${encodeURIComponent(params[k]).replace(/%2A/gi, '*').replace(/%7E/g, '~')}`).join('&');
  const url = `${BASE}${path}?${qs}`;
  const headers = {
    'User-Agent': UA, Accept: 'application/json, application/x-protobuf',
    'Content-Type': 'application/json; charset=utf-8',
  };
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const mod = u.protocol === 'https:' ? require('https') : require('http');
    const req = mod.request(u, { method: 'POST', headers }, res => {
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

/** 单集取流：多清晰度选 ≤1080 最高档（与 Kotlin stream(maxQuality=1080) 一致），返回 { url, altUrls, key }。 */
async function videoModel(vid) {
  const body = Buffer.from(JSON.stringify({
    biz_param: {
      detail_page_version: 0, device_level: 3, disable_digg_stat: false,
      need_all_video_definition: true, need_mp4_align: false,
      use_os_player: false, use_server_dns: false, video_platform: 1024,
    },
    dr_scene: 'preload',
    mixed_video_id_map: { 1004: [String(vid)] },
  }), 'utf8');
  const json = await request('/novel/player/multi_video_model/preload/v1', body);
  if (json.Code !== undefined && json.Code !== 0 && json.Code !== '0') throw new Error(`取流拒绝 Code=${json.Code}`);
  // preload 返回结构：data[vid].video_model（JSON 字符串，内部才是多清晰度列表）
  const entry = (json.data || {})[String(vid)] || {};
  let model = entry.video_model || {};
  // 红果返回的 video_model 是 JSON 字符串（内部才是多清晰度列表），需先 parse
  if (typeof model === 'string') {
    try { model = JSON.parse(model); } catch { model = {}; }
  }
  const rawList = model.video_list;
  const variants = Array.isArray(rawList) ? rawList : Object.values(rawList || {});
  // 收集变体全部播放地址（main_url + backup_url 系列，兼容 base64 编码）——与 guoapp/juku 一致，
  // main CDN 域被拒/不可达时用备用 CDN 域，避免整集降级网页源
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
  // ByteVC2 资源在 Android extractor 只有音频没视频轨，绝不选
  const rows = variants
    .map(o => {
      const urls = collectUrls(o);
      const meta = o.video_meta || {};
      const label = meta.definition || meta.vheight || '';
      return { q: qualityNumber(label), urls, meta, o };
    })
    .filter(r => r.urls.length > 0 && !/bytevc2|bvc2/i.test(((r.meta || {}).codec_type) || ''))
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

module.exports = { videoDetail, videoModel, deriveKey, searchSeries, fetchWebUrl };
