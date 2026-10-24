// 本地验证黄果下载链路：页面解析 videoSrc → master m3u8 请求（identity）→ 展开前 5 行 + 统计
const pf = require('../proxy-fetch.js');

function fetch(url, referer, maxBytes) {
  return new Promise((resolve, reject) => {
    pf.requestThroughProxy(url, '', {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
        'Accept': '*/*',
        'Accept-Encoding': 'identity',
        ...(referer ? { Referer: referer } : {}),
      },
      timeout: 25000, maxBytes: maxBytes || 5 * 1024 * 1024, redirects: 5,
    }, (err, r) => {
      if (err) return reject(err);
      if (!r || r.status < 200 || r.status >= 300) return reject(new Error('HTTP ' + (r && r.status)));
      resolve(r.body);
    });
  });
}

(async () => {
  const seriesId = process.argv[2] || '10194';
  const pageUrl = `https://www.pqhybuxo.cc/video/${seriesId}/`;
  console.log('1. 抓详情页:', pageUrl);
  const page = await fetch(pageUrl, pageUrl, 5 * 1024 * 1024);
  const html = page.toString('utf8');
  console.log('   页面字节:', page.length, 'html 长度:', html.length);
  const jsonText = (html.match(/<script[^>]*id="videoInitialData"[^>]*>([\s\S]*?)<\/script>/) || [])[1]
    || (html.match(/videoInitialData["']?\s*[:=]\s*(\{[\s\S]*?\})\s*;/) || [])[1];
  if (!jsonText) { console.log('   !! 无 videoInitialData'); return; }
  const data = JSON.parse(jsonText.replace(/^[\s\S]*?(?=\{)/, ''));
  const src = data.videoSrc || (Array.isArray(data.videoSrcList) ? data.videoSrcList[0] : '');
  console.log('   videoSrc:', src);
  if (!src) return;

  console.log('\n2. 请求 master m3u8 (identity):');
  const m3 = await fetch(src, pageUrl, 10 * 1024 * 1024);
  console.log('   字节:', m3.length, '前3字节:', m3.slice(0, 3).toString('hex'));
  const text = m3.toString('utf8');
  let bad = 0;
  for (const ch of text) { if (ch === '\uFFFD' || (ch.charCodeAt(0) < 32 && ch !== '\n' && ch !== '\r' && ch !== '\t')) bad++; }
  console.log('   乱码字符占比:', (bad / text.length * 100).toFixed(3) + '%');
  const lines = text.split(/\r?\n/).filter(l => l.trim() && !l.trim().startsWith('#'));
  console.log('   非注释行数:', lines.length);
  const sample = lines.slice(0, 3).map(l => String(l).slice(0, 120));
  console.log('   前3行:', JSON.stringify(sample, null, 1));
  process.exit(0);
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
