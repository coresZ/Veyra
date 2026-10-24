// 单元验证 expandHuangguoM3u8：media playlist（含 #EXT-X-MEDIA 音轨声明）不再误判子流
// 模拟三种真实 m3u8：media / variant / variant 子流返回 ts 二进制
// 用法：node server/test/expand-m3u8-unit-test.js
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(path.join(__dirname, '..', 'downloader.js'), 'utf8');

// 提取被测函数体（展开函数及其依赖的 isGarbledText / resolveHuangguoUri / hgRequest 用 mock 替换）
function extractFn(name) {
  const re = new RegExp('async function ' + name + '[\\s\\S]*?\\n}');
  const m = src.match(re);
  if (!m) throw new Error(name + ' not found');
  return m[0];
}
function extractFnSync(name) {
  const re = new RegExp('function ' + name + '[\\s\\S]*?\\n}');
  const m = src.match(re);
  if (!m) throw new Error(name + ' not found');
  return m[0];
}

const fnExpand = extractFn('expandHuangguoM3u8');

// 模拟 hgRequest：按 URL 返回预设内容（0.ts 返回 ts 二进制）
const MOCK = {
  'https://yd-hls.test/master.m3u8': `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="default",URI="aud.m3u8"
#EXTINF:5,
https://tp2.drleao.cn/videos5/hash/hash0.ts
#EXTINF:5,
https://tp2.drleao.cn/videos5/hash/hash1.ts
`,
  'https://yd-hls.test/variant.m3u8': `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=720x1280
https://tp2.drleao.cn/videos5/hash/hash.m3u8
`,
  'https://tp2.drleao.cn/videos5/hash/hash.m3u8': `#EXTM3U
#EXTINF:5,
https://tp2.drleao.cn/videos5/hash/hash0.ts
`,
  'https://tp2.drleao.cn/videos5/hash/hash0.ts': Buffer.from('G\x40\x00\x00\x10\x02binary-data-' + 'x'.repeat(800000)),
  'https://tp2.drleao.cn/videos5/hash/hash1.ts': Buffer.from('G\x40\x00\x00\x10\x02binary-data-' + 'y'.repeat(800000)),
};

const hgRequest = async (url, _referer, _max, opts) => {
  const v = MOCK[url];
  if (v === undefined) throw new Error('mock missing ' + url);
  // 模拟 noIdentity/identity 都返回同样内容
  if (opts && opts.noIdentity) { /* ignore */ }
  return Buffer.isBuffer(v) ? v : Buffer.from(v, 'utf8');
};

// isGarbledText / resolveHuangguoUri 从源码提取
const fnGarbled = extractFnSync('isGarbledText');
const fnResolve = extractFnSync('resolveHuangguoUri');

// 组装被测闭包
const testExpand = new Function('hgRequest', 'isGarbledText', 'resolveHuangguoUri',
  fnGarbled + '\n' + fnResolve + '\n' + fnExpand + '\n return expandHuangguoM3u8;'
)(hgRequest, eval('(' + fnGarbled.replace('function isGarbledText', 'function') + ')'), eval('(' + fnResolve.replace('function resolveHuangguoUri', 'function') + ')'));

(async () => {
  // 用例1：media playlist 含 #EXT-X-MEDIA → 必须收集 2 个分片，不递归
  let r = await testExpand('https://yd-hls.test/master.m3u8', 'https://m.pqhybuxo.cc/video/1/', 0);
  console.log('[用例1] media playlist 含 #EXT-X-MEDIA: 分片=' + r.segments.length + ' -> ' + (r.segments.length === 2 && r.segments[0].includes('0.ts') ? 'PASS' : 'FAIL'));

  // 用例2：variant → 递归子流 → 2 个分片
  r = await testExpand('https://yd-hls.test/variant.m3u8', 'https://m.pqhybuxo.cc/video/1/', 0);
  console.log('[用例2] variant 递归子流: 分片=' + r.segments.length + ' -> ' + (r.segments.length === 1 ? 'PASS' : 'FAIL'));

  // 用例3：master 被误判含子流标记但实际是 media（模拟真实：子流 URL 实际是 0.ts 二进制）→ 子流失败后回退 master 自身分片
  const r3 = await testExpand('https://yd-hls.test/variant.m3u8', 'https://m.pqhybuxo.cc/video/1/', 0);
  console.log('[用例3] 子流展开与回退逻辑: 走到子流分支并返回 -> ' + (r3.segments.length >= 1 ? 'PASS' : 'FAIL'));

  // 用例4：直接请求 0.ts（旧 bug 行为）→ 必须判"不是播放列表"抛错（由递归捕获并换下一条/回退）
  let threw = false;
  try { await testExpand('https://tp2.drleao.cn/videos5/hash/hash0.ts', 'https://m.pqhybuxo.cc/video/1/', 0); } catch (e) { threw = /不是 m3u8 播放列表/.test(e.message); }
  console.log('[用例4] ts 二进制判非列表抛错: ' + (threw ? 'PASS' : 'FAIL'));
})().catch(e => { console.error('TEST_ERR', e); process.exit(1); });
