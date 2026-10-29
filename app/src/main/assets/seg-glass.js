/* 分段控件液态玻璃交互层（v0.20.5）
 * 适用：我的页「观看历史/我的收藏/我的下载」、分类页子标签（.segmented / .compact-tabs）。
 *
 * 逻辑 1:1 对齐 dock-glass.js（底栏），两者同源同手感：
 *   1) 每条 tab 栏内各挂一枚玻璃透镜（.seg-blob > b），静止时停在 .selected 那格下面；
 *   2) 按住任一格 → 透镜先吸附过去，横向拖动时透镜跟手并被「拿起」（.dragging 放大），
 *      被拖到的格文字加深（.drag-over），其余变淡（.dim）；
 *   3) 松手：停在别的格上 → 派发该格的 click（交给 app.js 原有 [data-library-tab] / [data-sub]
 *      导航链路），原地/空白 → 弹簧弹回当前选中格。
 * 不接管导航，只做手势与视觉 —— 和 dock-glass.js 的分工一致。
 *
 * 对外还暴露 setProgress(from,to,q)：给「内容区横滑切页」复用同一枚透镜做跟手插值，
 * 避免出现两套浮标（拖动一个样、落地又一个样）。
 */
(() => {
  const PAD = 4;            // 透镜相对按钮左右各内缩（与 #dock-blob 一致）
  const DRAG_SLOP = 7;      // 超过这个位移才算拖动，否则算点击
  const SELECTOR = '[role="tablist"].segmented,[role="tablist"].compact-tabs';

  const listOf = bar => Array.from(bar.querySelectorAll('button'));
  const currentOf = bar => bar.querySelector('button.selected') || listOf(bar)[0] || null;

  function buzz(ms) {
    try { navigator.vibrate && navigator.vibrate(ms); } catch (_) { }
  }

  /** 取句柄：bar 上已有则复用（克隆体也会带 .seg-blob，能被认出来） */
  function handle(bar) {
    if (!bar || !bar.nodeType) return null;
    if (bar.__seg) return bar.__seg;
    let blob = null;
    for (const kid of bar.children) if (kid.classList.contains('seg-blob')) { blob = kid; break; }
    if (!blob) {
      blob = document.createElement('i');
      blob.className = 'seg-blob';
      blob.setAttribute('aria-hidden', 'true');
      blob.appendChild(document.createElement('b'));
      bar.insertBefore(blob, bar.firstChild);
    }
    const h = {
      bar, blob,
      dragging: false, pointerId: null, startX: 0, startY: 0,
      hovered: undefined, raf: 0, widthPx: 0,
    };

    h.metrics = el => {
      const d = bar.getBoundingClientRect();
      const b = el.getBoundingClientRect();
      return { x: b.left - d.left, w: b.width, barW: d.width };
    };

    h.rects = () => {
      const d = bar.getBoundingClientRect();
      return listOf(bar).map(el => {
        const r = el.getBoundingClientRect();
        return { l: r.left - d.left, t: r.top - d.top, w: r.width, h: r.height };
      });
    };

    /** 静止/切换：透镜对齐到某个按钮并弹簧滑过去 */
    h.paint = (el, animate = true) => {
      if (!el) return;
      const m = h.metrics(el);
      const w = Math.max(0, m.w - PAD * 2);
      if (!animate) h.blob.style.transition = 'none';
      h.blob.style.width = w + 'px';
      h.blob.style.transform = `translate3d(${m.x + PAD}px,0,0)`;
      h.widthPx = w;
      if (!animate) {
        void h.blob.offsetWidth;      // 强制回流，避免首帧从 -999px 滑进来
        h.blob.style.transition = '';
      }
    };

    h.sync = animate => h.paint(currentOf(bar), animate);
    h.animate = () => {
      if (h.raf) return;
      h.raf = requestAnimationFrame(() => { h.raf = 0; h.sync(true); });
    };

    /** 拖动中：透镜中心跟手，左右夹在栏内（不越界） */
    h.follow = clientX => {
      const d = bar.getBoundingClientRect();
      const w = h.widthPx || (d.width / Math.max(1, listOf(bar).length)) - PAD * 2;
      let x = clientX - d.left - w / 2;
      x = Math.max(PAD, Math.min(d.width - w - PAD, x));
      h.blob.style.transform = `translate3d(${x}px,0,0)`;
    };

    /** 内容区横滑时的跟手插值：from→to 两格之间按进度连续过渡 */
    h.progress = (from, to, ratio) => {
      const items = listOf(bar), rects = h.rects();
      if (!items.length || rects[from] == null || rects[to] == null) return;
      const q = Math.max(0, Math.min(1, ratio || 0));
      const mix = (a, b) => a + (b - a) * q;
      const A = rects[from], B = rects[to];
      // 注意：这里不要写 inline transition —— 收尾动画要靠 swipeCloseTrack 设的行内过渡生效，
      // 一写 none 就变成瞬跳。跟手期间的无过渡由 .dragging 类负责。
      h.blob.style.width = `${mix(A.w, B.w) - PAD * 2}px`;
      h.blob.style.height = `${mix(A.h, B.h)}px`;
      h.blob.style.transform = `translate3d(${mix(A.l, B.l) + PAD}px, ${(A.t + 0)}px, 0)`;
      h.widthPx = mix(A.w, B.w) - PAD * 2;
      items.forEach((el, i) => el.classList.toggle('dim', i !== to && i !== from));
      items[to].classList.toggle('drag-over', q > 0.5);
    };

    h.setHover = el => {
      // 手指落在格与格之间的缝隙 / 栏外时保持上一格的悬停态：
      // 一旦这里清成 null，drag-over 与 dim 会被抹平，相邻项在拖动中就会反复闪一下，
      // 正是「旁边的项背景突然变色」的观感来源。只有松手才真正清空。
      if (!el) return;
      if (el === h.hovered) return;
      h.hovered = el;
      const cur = currentOf(bar);
      listOf(bar).forEach(b => {
        b.classList.toggle('drag-over', b === el && b !== cur);
        b.classList.toggle('dim', !!el && b !== el);
      });
      if (el) {
        const m = h.metrics(el);
        h.widthPx = Math.max(0, m.w - PAD * 2);
        h.blob.style.width = h.widthPx + 'px';
      }
    };

    h.clearHover = () => {
      h.hovered = undefined;
      listOf(bar).forEach(b => b.classList.remove('drag-over', 'dim', 'holding'));
    };

    h.setDragging = on => bar.classList.toggle('dragging', !!on);

    /** 交还静止态：撤掉过渡强关、按 .selected 归位 */
    h.release = () => {
      h.setDragging(false);
      h.clearHover();
      h.blob.style.transition = '';
      h.blob.style.height = '';
      h.animate();
    };

    bar.__seg = h;
    if (!bar.hasAttribute('data-seg-no-gesture')) bind(h);
    h.sync(false);
    return h;
  }

  /* ---------------- 手势：与 dock-glass.js 同结构 ---------------- */
  function bind(h) {
    const bar = h.bar;
    let suppressClickUntil = 0;

    bar.addEventListener('pointerdown', e => {
      if (e.button != null && e.button !== 0) return;
      const btn = e.target.closest('button');
      if (!btn) return;
      h.pointerId = e.pointerId;
      h.startX = e.clientX; h.startY = e.clientY;
      h.dragging = false;
      h.hovered = undefined;
      try { bar.setPointerCapture(e.pointerId); } catch (_) { }
      btn.classList.add('holding');
      h.paint(btn, true);          // 按下即成为焦点：透镜先吸附过去
    }, { passive: true });

    bar.addEventListener('pointermove', e => {
      if (h.pointerId === null || e.pointerId !== h.pointerId) return;
      const dx = e.clientX - h.startX, dy = e.clientY - h.startY;
      if (!h.dragging) {
        if (Math.abs(dx) < DRAG_SLOP && Math.abs(dy) < DRAG_SLOP) return;
        if (Math.abs(dx) <= Math.abs(dy)) return;        // 竖向 → 让页面滚动
        h.dragging = true;
        h.setDragging(true);
        buzz(8);
      }
      h.follow(e.clientX);
      const hit = document.elementFromPoint(e.clientX, e.clientY);
      const btn = hit ? hit.closest('button') : null;
      h.setHover(btn && bar.contains(btn) ? btn : null);
    }, { passive: true });

    const finish = (e, cancelled) => {
      if (h.pointerId === null || (e && e.pointerId !== h.pointerId)) return;
      const pid = h.pointerId;
      const wasDragging = h.dragging;
      const target = h.hovered;
      h.pointerId = null; h.dragging = false;
      try { bar.releasePointerCapture(pid); } catch (_) { }
      h.release();
      if (cancelled) { suppressClickUntil = 0; return; }
      const cur = currentOf(bar);
      let hit = target;
      if (!hit && e) {
        const el = document.elementFromPoint(e.clientX ?? 0, e.clientY ?? 0);
        const btn = el ? el.closest('button') : null;
        hit = btn && bar.contains(btn) ? btn : null;
      }
      if (wasDragging) buzz(6);
      // 一律自己派发 click：pointer capture 会把浏览器补发的 click 重定向到栏身上，
      // 届时 closest('[data-library-tab]') 取不到按钮——轻点就会「没反应」。
      // 轻点（没拖动）时即使原地重复点也照常派发，保持与浏览器原生点击一致的行为。
      if (hit && (!wasDragging || hit !== cur)) hit.click();
      suppressClickUntil = performance.now() + 350;   // 吃掉系统补发的那次 click
    };

    bar.addEventListener('pointerup', e => finish(e, false));
    bar.addEventListener('pointercancel', e => finish(e, true));
    bar.addEventListener('lostpointercapture', e => { if (h.pointerId !== null) finish(e, true); });

    /* 拖动后吞掉浏览器补发的那次 click，避免再导航一次 */
    bar.addEventListener('click', e => {
      if (performance.now() < suppressClickUntil) { e.stopPropagation(); e.preventDefault(); }
    }, true);

    h.__suppress = () => suppressClickUntil;

    /* selected 类由 app.js 切换，这里同步透镜位置 */
    const mo = new MutationObserver(() => { if (!h.dragging) h.animate(); });
    listOf(bar).forEach(b => mo.observe(b, { attributes: true, attributeFilter: ['class'] }));
    bar.__segMO = mo;
  }

  /* ---------------- 自动挂载：tab 栏随页面重渲染重建 ---------------- */
  function attachAll(root) {
    if (!root || !root.querySelectorAll) return;
    for (const bar of root.querySelectorAll(SELECTOR)) handle(bar);
  }

  function syncAll(root) {
    if (!root || !root.querySelectorAll) return;
    for (const bar of root.querySelectorAll(SELECTOR)) {
      if (bar.__seg && !bar.__seg.dragging) bar.__seg.animate();
    }
  }

  const target = document.getElementById('content') || document.body;
  const mo = new MutationObserver(records => {
    for (const r of records) {
      for (const node of r.addedNodes) {
        if (node.nodeType !== 1) continue;
        if (node.matches && node.matches(SELECTOR)) handle(node);
        attachAll(node);
      }
    }
  });
  try { mo.observe(target, { childList: true, subtree: true }); } catch (_) { }
  addEventListener('resize', () => syncAll(target));
  addEventListener('orientationchange', () => setTimeout(() => syncAll(target), 260));

  window.SegGlass = { handle, attachAll, syncAll };
  attachAll(target);
})();
