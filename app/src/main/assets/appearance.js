/* 外观预设数据 —— 逐字取自 Sunny UI 的 assets/appearance/presets.ts（Apache-2.0）。
 * materials 的 sample 就是原稿材质缩略块的 CSS，直接内联到缩略图上，不用手绘替代。 */
var SUNNY_MATERIALS = [{"id":"soft","name":"柔软浮雕","sample":"background:linear-gradient(158deg,#f8f9fb,#edf0f4);border:1px solid rgba(26,42,68,.05);box-shadow:4px 4px 9px rgba(26,42,68,.16),-3px -3px 7px #fff"},{"id":"clear","name":"清晰默认","sample":"background:#fff;border:1px solid #e4e8ef;box-shadow:0 1px 2px rgba(16,26,42,.08)"},{"id":"paper","name":"纸页档案","sample":"background:linear-gradient(110deg,rgba(139,116,82,.07),transparent 45%),#fffdf7;border:1px solid #e0d6c3;box-shadow:inset 0 0 0 2px #fffefb,0 2px 0 rgba(139,116,82,.18)"},{"id":"linen","name":"亚麻织纹","sample":"background:repeating-linear-gradient(45deg,rgba(110,100,80,.13) 0 1px,transparent 1px 3px),repeating-linear-gradient(-45deg,rgba(110,100,80,.11) 0 1px,transparent 1px 3px),#f6f4ee;border:1px solid #d5d0c2"},{"id":"glass","name":"iOS 玻璃","sample":"background:linear-gradient(135deg,rgba(255,255,255,.92),rgba(214,228,246,.5));border:1px solid rgba(255,255,255,.95);box-shadow:inset 0 1px 0 #fff,0 4px 10px rgba(16,26,42,.14)"},{"id":"frosted","name":"雾面玻璃","sample":"background:rgba(255,255,255,.68);border:1px solid rgba(255,255,255,.95);box-shadow:inset 0 2px 0 #fff,inset 0 -3px 6px rgba(20,30,45,.16)"},{"id":"aurora","name":"极光玻璃","sample":"background:linear-gradient(120deg,rgba(255,255,255,.8),rgba(110,205,225,.55) 45%,rgba(200,150,240,.5));border:1px solid rgba(255,255,255,.8);box-shadow:0 3px 10px rgba(70,140,170,.2)"},{"id":"duotone","name":"双色渐层","sample":"background:linear-gradient(125deg,color-mix(in srgb,var(--accent) 58%,#fff),color-mix(in srgb,var(--accent-2) 58%,#fff));border:1px solid color-mix(in srgb,var(--accent) 22%,#fff)"},{"id":"satin","name":"缎光瓷面","sample":"background:linear-gradient(155deg,#fff,#e6edf8 38%,#fff 56%,#f0eef9);border:1px solid #e3e8f0;box-shadow:inset 0 1px 1px #fff,0 3px 8px rgba(24,38,61,.1)"},{"id":"outline","name":"精致描边","sample":"background:#fff;border:1px solid color-mix(in srgb,var(--accent) 48%,#fff);box-shadow:0 2px 0 color-mix(in srgb,var(--accent) 20%,transparent)"},{"id":"metal","name":"拉丝金属","sample":"background:linear-gradient(105deg,transparent 26%,rgba(255,255,255,.65) 44%,transparent 60%),repeating-linear-gradient(0deg,rgba(255,255,255,.9) 0 1px,rgba(120,134,152,.22) 1px 2px,transparent 2px 4px),linear-gradient(180deg,#eef3f7,#c9d2dc);border:1px solid #aeb9c7;box-shadow:inset 0 1px 0 #fff,inset 0 -2px 0 #9ca8b8"},{"id":"clay","name":"陶土哑光","sample":"background:radial-gradient(120% 100% at 20% 0%,#fbf2e7,#e8d5c3);border:1px solid rgba(160,130,100,.24);box-shadow:0 7px 14px rgba(120,86,58,.24),inset 0 2px 2px rgba(255,255,255,.8)"},{"id":"marble","name":"大理石","sample":"background:conic-gradient(from 118deg at 26% 38%,rgba(150,162,180,.3),transparent 12%,rgba(190,198,210,.26) 26%,transparent 42%,rgba(140,152,170,.28) 58%,transparent 76%),linear-gradient(160deg,#fff,#ecf0f4);border:1px solid rgba(150,160,175,.32);box-shadow:inset 0 1px 0 #fff"},{"id":"neon","name":"霓虹暗夜","sample":"background:linear-gradient(160deg,#1a2236,#0b0f17);border:1px solid color-mix(in srgb,var(--accent) 65%,transparent);box-shadow:0 0 10px color-mix(in srgb,var(--accent) 50%,transparent),inset 0 0 10px color-mix(in srgb,var(--accent) 30%,transparent)"},{"id":"cutout","name":"纸片拼贴","sample":"background:#fff;border:1px solid color-mix(in srgb,var(--accent) 58%,#93a2b6);box-shadow:3px 3px 0 color-mix(in srgb,var(--accent) 34%,transparent)"},{"id":"mesh","name":"雾面色场","sample":"background:radial-gradient(60% 80% at 18% 12%,color-mix(in srgb,var(--accent) 60%,#fff),transparent 72%),radial-gradient(65% 85% at 88% 82%,color-mix(in srgb,var(--accent-2) 60%,#fff),transparent 72%),#f7f8fb;border:1px solid transparent;box-shadow:0 4px 10px rgba(20,30,50,.1)"}];
var SUNNY_COLORS_SINGLE = [{"id":"blue","name":"晴蓝","light":"#376bd8","dark":"#9dbbff"},{"id":"celadon","name":"青瓷","light":"#2f7f76","dark":"#8fd6c9"},{"id":"green","name":"松绿","light":"#188466","dark":"#7fd3b0"},{"id":"violet","name":"鸢紫","light":"#8855cc","dark":"#c0a4f5"},{"id":"rose","name":"莓红","light":"#c34278","dark":"#f0a3bf"},{"id":"brick","name":"砖窑","light":"#a8433a","dark":"#eda79e"},{"id":"amber","name":"琥珀","light":"#b27a16","dark":"#ebc078"},{"id":"mustard","name":"芥末","light":"#8f7a12","dark":"#ddc66a"},{"id":"indigo","name":"靛蓝","light":"#3b4fa8","dark":"#a9b6f0"},{"id":"slate","name":"石墨","light":"#61758f","dark":"#b2c1d4"}];
var SUNNY_COLORS_MULTI = [{"id":"catppuccin","name":"奶霜花园","light":["#c14f7c","#179299","#d20f39"],"dark":["#f0acc7","#94e2d5","#f5c2e7"]},{"id":"nord","name":"北境极光","light":["#427fa4","#5e678d","#42776c"],"dark":["#88c0d0","#b48ead","#a3be8c"]},{"id":"iris","name":"鸢尾暮色","light":["#7958c8","#b34379","#22778a"],"dark":["#b7a5ff","#f3a6ce","#79d1de"]},{"id":"deepsea","name":"深海鎏金","light":["#1d6f8b","#b4892f","#4c5d8a"],"dark":["#7fd2e8","#ecd08a","#a9b6e0"]},{"id":"coast","name":"海盐日落","light":["#14869a","#b64d38","#866519"],"dark":["#79d5e5","#ffaf98","#edcf86"]},{"id":"lava","name":"暮色熔岩","light":["#b4465e","#7a4c93","#d97a3d"],"dark":["#f2a5b2","#c3a6ea","#f6c39a"]},{"id":"macaron","name":"马卡龙","light":["#329383","#9461ac","#b35a70"],"dark":["#a7dfd0","#dab6ef","#f4bacb"]},{"id":"cedar","name":"薄荷雪松","light":["#2e8b74","#8fa06a","#c9a227"],"dark":["#9fe0c9","#cfd9a8","#ecd08a"]},{"id":"peach","name":"蜜桃乌龙","light":["#c16648","#7e6541","#6b7d49"],"dark":["#f5b79d","#e2cb9d","#c8d7a7"]},{"id":"lavender","name":"蓝调花雾","light":["#467fb2","#7758a6","#9b586e"],"dark":["#a5c7e8","#c9b4ef","#e8b4c7"]},{"id":"mist","name":"海雾晨光","light":["#4f7fa8","#8fb9c9","#d9a98a"],"dark":["#a9cbe8","#cfe6ec","#f0cfb4"]},{"id":"forest","name":"雨后花园","light":["#3d895f","#8a6532","#625eaa"],"dark":["#9bd0af","#dfc392","#bdb8ef"]},{"id":"honey","name":"蜂蜜麦田","light":["#a17a24","#397976","#ad6556"],"dark":["#e4c278","#9dcfcb","#e5b0a3"]},{"id":"oasis","name":"沙漠绿洲","light":["#b07a2e","#4e8a6b","#8a5a3c"],"dark":["#eccd94","#a8d4b6","#d5ad8c"]},{"id":"ruby","name":"红茶玫瑰","light":["#c04757","#567647","#9b742e"],"dark":["#ee9aa5","#b5cda4","#dfc48a"]},{"id":"nightviolet","name":"紫罗兰夜","light":["#6c4ab6","#c05a8e","#2f8fa8"],"dark":["#bfa6f5","#f0a8c2","#8fd8e6"]}];
var SUNNY_CORNERS = [{"id":"tight","name":"紧致"},{"id":"default","name":"标准"},{"id":"round","name":"圆润"}];

/* Sunny UI 外观四维：明暗 × 质感 × 配色 × 圆角。四者独立保存，互不重置。 */
(function () {
  'use strict';
  var KEY = 'veyra.appearance.v1';
  var DEFAULT = { theme: 'light', material: 'soft', color: 'blue', corner: 'default' };
  var root = document.documentElement;

  function read() {
    var out = {};
    for (var k in DEFAULT) out[k] = DEFAULT[k];
    try {
      var raw = localStorage.getItem(KEY);
      if (raw) {
        var saved = JSON.parse(raw);
        for (var k2 in DEFAULT) if (saved[k2]) out[k2] = saved[k2];
      }
    } catch (e) {}
    return out;
  }

  var state = read();

  function paint() {
    root.dataset.theme = state.theme;
    root.dataset.material = state.material;
    root.dataset.color = state.color;
    root.dataset.corner = state.corner;
    // 系统状态栏/导航栏跟随明暗（原生侧读的是同一个 data-theme）
    var meta = document.querySelector('meta[name="color-scheme"]');
    if (meta) meta.setAttribute('content', state.theme === 'dark' ? 'dark' : 'light');
  }

  function save() {
    try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (e) {}
  }

  window.VeyraAppearance = {
    get: function () { var o = {}; for (var k in state) o[k] = state[k]; return o; },
    set: function (patch) {
      for (var k in patch) if (patch.hasOwnProperty(k) && state.hasOwnProperty(k)) state[k] = patch[k];
      paint(); save();
      if (typeof window.VeyraAppearance.onChange === 'function') window.VeyraAppearance.onChange(state);
    }
  };

  paint();

  /* ---------- 设置页 UI ---------- */
  function esc(v) { return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  }); }

  function matGrid() {
    return SUNNY_MATERIALS.map(function (m) {
      return '<button type="button" class="mat-tile" data-mat="' + m.id + '" aria-pressed="false">'
        + '<i style="' + esc(m.sample) + '"></i><span>' + m.name + '</span></button>';
    }).join('');
  }

  // 原稿取色是 item[theme]：色点必须显示「切换后真正会应用的那一版」。
  // 这里不重渲染，直接把浅/深两版都写进自定义属性，由 [data-theme=dark] 选择器切换。
  function swatch(v) {
    return Array.isArray(v)
      ? 'conic-gradient(from -90deg,' + v[0] + ',' + v[1] + ',' + v[2] + ',' + v[0] + ')'
      : v;
  }

  function dot(c) {
    return '<button type="button" class="color-dot" data-color="' + c.id + '" aria-pressed="false"'
      + ' title="' + c.name + '" aria-label="' + c.name + '"'
      + ' style="--dot:' + swatch(c.light) + ';--dot-d:' + swatch(c.dark) + '"></button>';
  }

  /* ---------- 设置页 UI：一行入口 + 弹窗 ----------
   * 四维组合铺在设置页里要占满一屏，把后面的设置项全挤下去。改成和「账号管理」一样的
   * 一行入口（副标题显示当前组合），点开才以浮层弹出完整选项；点遮罩/关闭/返回导航都会收起。 */
  function findById(list, id) {
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }

  function cornerName() {
    var c = findById(SUNNY_CORNERS, state.corner);
    return c ? c.name : state.corner;
  }

  function summaryText() {
    var m = findById(SUNNY_MATERIALS, state.material);
    var c = findById(SUNNY_COLORS_SINGLE, state.color) || findById(SUNNY_COLORS_MULTI, state.color);
    return (m ? m.name : state.material) + ' · ' + (c ? c.name : state.color)
      + ' · ' + (state.theme === 'dark' ? '深色' : '浅色') + ' · ' + cornerName() + '圆角';
  }

  function updateSummary() {
    var el = document.querySelector('[data-appearance-summary]');
    if (el) el.textContent = summaryText();
  }

  var ARROW = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"'
    + ' stroke-linecap="round" stroke-linejoin="round"><path d="m9 5 7 7-7 7"/></svg>';

  function optionsHtml() {
    var single = SUNNY_COLORS_SINGLE.map(dot).join('');
    var multi = SUNNY_COLORS_MULTI.map(dot).join('');
    return '<div class="appearance-block"><span class="appearance-label">明暗</span>'
      +   '<div class="seg" role="group" aria-label="明暗">'
      +     '<button type="button" data-theme-set="light">浅色</button>'
      +     '<button type="button" data-theme-set="dark">深色</button>'
      +   '</div></div>'
      + '<div class="appearance-block"><span class="appearance-label">质感</span>'
      +   '<div class="mat-grid">' + matGrid() + '</div></div>'
      + '<div class="appearance-block"><span class="appearance-label">单色</span>'
      +   '<div class="color-row">' + single + '</div></div>'
      + '<div class="appearance-block"><span class="appearance-label">多色</span>'
      +   '<div class="color-row">' + multi + '</div></div>'
      + '<div class="appearance-block"><span class="appearance-label">圆角</span>'
      +   '<div class="seg" role="group" aria-label="圆角">'
      +     '<button type="button" data-corner-set="tight">紧致</button>'
      +     '<button type="button" data-corner-set="default">标准</button>'
      +     '<button type="button" data-corner-set="round">圆润</button>'
      +   '</div></div>';
  }

  window.renderAppearance = function () {
    return '<section><button type="button" class="settings-link appearance-entry"'
      + ' data-appearance-open aria-haspopup="dialog">'
      + '<span><strong>外观</strong><small data-appearance-summary>' + esc(summaryText()) + '</small></span>'
      + ARROW + '</button></section>';
  };

  var scrim = null;

  function ensurePop() {
    if (scrim && document.body.contains(scrim)) return scrim;
    scrim = document.createElement('div');
    scrim.className = 'appearance-scrim';
    scrim.hidden = true;
    scrim.innerHTML = '<div class="appearance-pop" role="dialog" aria-modal="true" aria-labelledby="appearance-title">'
      + '<div class="appearance-head"><h2 id="appearance-title">外观</h2>'
      +   '<button type="button" class="appearance-close" data-appearance-close aria-label="关闭">✕</button></div>'
      + '<p class="appearance-note">明暗、质感、配色、圆角各存一份，互不影响。</p>'
      + '<div class="appearance" id="appearance-panel">' + optionsHtml() + '</div></div>';
    document.body.appendChild(scrim);
    scrim.addEventListener('click', function (e) {
      // 点遮罩空白处或关闭按钮 → 收起；点选项 → 就地改状态，弹窗不关
      if (e.target === scrim || e.target.closest('[data-appearance-close]')) { closePop(); return; }
      var t = e.target.closest('[data-theme-set],[data-mat],[data-color],[data-corner-set]');
      if (!t) return;
      if (t.dataset.themeSet) window.VeyraAppearance.set({ theme: t.dataset.themeSet });
      else if (t.dataset.mat) window.VeyraAppearance.set({ material: t.dataset.mat });
      else if (t.dataset.color) window.VeyraAppearance.set({ color: t.dataset.color });
      else if (t.dataset.cornerSet) window.VeyraAppearance.set({ corner: t.dataset.cornerSet });
      syncSelection();
      updateSummary();
    });
    return scrim;
  }

  function openPop() {
    ensurePop();
    syncSelection();
    scrim.hidden = false;
    document.body.classList.add('appearance-open');
  }

  function closePop() {
    if (!scrim || scrim.hidden) return;
    scrim.hidden = true;
    document.body.classList.remove('appearance-open');
  }

  /** 系统侧滑/返回键拦截入口（由 app.js hgBack 调用）：外观弹窗开着时先关弹窗，返回 true 表示已消费。 */
  window.__veyraAppearanceBack = function () {
    if (scrim && !scrim.hidden) { closePop(); return true; }
    return false;
  }

  /** 设置页渲染完调用：刷新入口文案/选中态，并挂一次性全局事件。 */
  window.bindAppearance = function () {
    updateSummary();
    syncSelection();
    if (window.__veyraAppearanceWired) return;
    window.__veyraAppearanceWired = true;
    document.addEventListener('click', function (e) {
      if (e.target.closest('[data-appearance-open]')) { openPop(); return; }
      // 离开设置页（切 Dock / 点返回）时顺手收起，避免弹窗留在别的页面上
      if (!scrim || scrim.hidden) return;
      if (e.target.closest('[data-nav],[data-back]')) closePop();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closePop();
    });
  };

  function syncSelection() {
    var panel = scrim && document.body.contains(scrim) ? scrim.querySelector('#appearance-panel')
      : document.getElementById('appearance-panel');
    if (!panel) return;
    var set = function (sel, attr, val) {
      panel.querySelectorAll(sel).forEach(function (b) {
        b.setAttribute('aria-pressed', b.getAttribute(attr) === val ? 'true' : 'false');
      });
    };
    set('[data-theme-set]', 'data-theme-set', state.theme);
    set('[data-mat]', 'data-mat', state.material);
    set('[data-color]', 'data-color', state.color);
    set('[data-corner-set]', 'data-corner-set', state.corner);
  }
})();
