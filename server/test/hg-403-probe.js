// 本地探针：红果 NAS 下载 403 定位——搜索剧→videoDetail 拿 vid→videoModel 取流→试下载首个 URL
// 用法：node test/hg-403-probe.js "剧名"
const hg = require('../hongguo-app.js');
const http = require('http');
const https = require('https');

function probeHead(url, headers, timeoutMs) {
  return new Promise((resolve) => {
    const mod = url.startsWith('https') ? https : http;
    const u = new URL(url);
    const req = mod.request(u, { method: 'GET', headers: headers || {}, timeout: timeoutMs || 15000 }, res => {
      res.resume();
      let len = 0;
      res.on('data', c => { len += c.length; if (len > 65536) { req.destroy(); } });
      res.on('end', () => resolve({ status: res.statusCode, bytes: len, headers: res.headers }));
    });
    req.on('error', e => resolve({ status: -1, error: e.message }));
    req.on('timeout', () => { req.destroy(); resolve({ status: -1, error: 'timeout' }); });
    req.end();
  });
}

(async () => {
  const title = process.argv[2] || '神雕：重生之杨过';
  console.log('=== 1. 标题搜索 ===');
  let sid = null;
  try {
    sid = await hg.searchSeries(title, 1);
    console.log('series_id =', sid);
  } catch (e) { console.log('searchSeries 失败:', e.message); }

  if (sid) {
    console.log('\n=== 2. videoDetail 拿 vids ===');
    try {
      const det = await hg.videoDetail(sid);
      console.log('vids 数量 =', det.vids.length, '前3:', det.vids.slice(0, 3));
      const vid = det.vids[0];
      console.log('\n=== 3. videoModel 取流第1集 ===');
      try {
        const s = await hg.videoModel(vid);
        console.log('quality =', s.quality, '| key =', s.key ? s.key.slice(0, 8) + '...' : '(无)');
        console.log('url =', s.url);
        console.log('altUrls =', (s.altUrls || []).length);
        const targets = [s.url, ...(s.altUrls || [])].slice(0, 4);
        console.log('\n=== 4. 直链下载探测（前64KB）===');
        for (const u of targets) {
          const r = await probeHead(u, { 'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36', 'Accept-Encoding': 'identity' });
          console.log(r.status, '|', String(u).slice(0, 90), r.error ? '| err:' + r.error : '');
        }
        // 带 Referer 再试
        console.log('\n--- 带 Referer 再试 ---');
        const host = new URL(s.url).host;
        for (const u of targets) {
          const r = await probeHead(u, { 'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36', 'Referer': 'https://' + host + '/' });
          console.log(r.status, '|', String(u).slice(0, 90));
        }
      } catch (e) { console.log('videoModel 失败:', e.message); }
    } catch (e) { console.log('videoDetail 失败:', e.message); }
  }

  console.log('\n=== 5. 网页源保底探测 ===');
  if (sid) {
    try {
      const web = await hg.fetchWebUrl(sid, 1);
      console.log('网页源 url =', web.url, '| referer =', web.referer);
      const r = await probeHead(web.url, { 'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36', 'Referer': web.referer + '/' });
      console.log('带Referer:', r.status, String(web.url).slice(0, 90));
      const r2 = await probeHead(web.url, { 'User-Agent': 'Mozilla/5.0' });
      console.log('不带Referer:', r2.status, String(web.url).slice(0, 90));
    } catch (e) { console.log('fetchWebUrl 失败:', e.message); }
  }
  process.exit(0);
})().catch(e => { console.error('probe error:', e); process.exit(1); });
