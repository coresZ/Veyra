/* 液态玻璃 Dock 交互层（v0.18.68）
 * 职责：
 *   1) 把 #dock-blob（玻璃透镜）同步到当前 active 按钮下面，切换 tab 时弹簧滑过去；
 *   2) 每个按钮都是「焦点」：按下即吸附过去，按住横向拖动时透镜跟手、
 *      手指所在的按钮放大高亮（drag-over），其余按钮变暗；
 *   3) 松手时若停在别的选项上 → 触发该选项（走 app.js 原有 click 导航链路），
 *      若拖到空白处/原地 → 弹回原焦点。
 * 不接管导航逻辑，只做手势与视觉；导航仍由 app.js 的 #dock [data-nav] click 处理。
 */
(() => {
  const dock = document.getElementById('dock');
  const blob = document.getElementById('dock-blob');
  if (!dock || !blob) return;

  const buttons = Array.from(dock.querySelectorAll('[data-nav]'));
  if (buttons.length < 2) return;

  const PAD = 4;          // 透镜相对按钮左右各内缩
  const DRAG_SLOP = 7;    // 超过这个位移才算拖动（否则当点击）
  let dragging = false;
  let pointerId = null;
  let startX = 0, startY = 0;
  let hovered = null;
  let suppressClickUntil = 0;
  let rafId = 0;

  const current = () => dock.querySelector('[data-nav].active') || buttons[0];

  function metrics(btn) {
    const d = dock.getBoundingClientRect();
    const b = btn.getBoundingClientRect();
    return { x: b.left - d.left, w: b.width, dockW: d.width };
  }

  function paint(btn, animate) {
    const m = metrics(btn);
    if (!animate) blob.style.transition = 'none';
    blob.style.width = Math.max(0, m.w - PAD * 2) + 'px';
    blob.style.transform = `translate3d(${m.x + PAD}px,0,0)`;
    if (!animate) {
      void blob.offsetWidth;          // 强制回流，避免首帧从初始位置滑入
      blob.style.transition = '';
    }
  }

  /* 拖动中：透镜中心跟手，左右夹取在 dock 内 */
  function follow(clientX) {
    const d = dock.getBoundingClientRect();
    const w = parseFloat(blob.style.width) || d.width / buttons.length;
    let x = clientX - d.left - w / 2;
    x = Math.max(PAD, Math.min(d.width - w - PAD, x));
    blob.style.transform = `translate3d(${x}px,0,0)`;
  }

  function animate() {
    if (rafId) return;
    rafId = requestAnimationFrame(() => { rafId = 0; paint(current(), true); });
  }

  function setHovered(btn) {
    if (btn === hovered) return;
    hovered = btn;
    buttons.forEach(b => {
      b.classList.toggle('drag-over', b === btn && b !== current());
      b.classList.toggle('dim', !!btn && b !== btn);
    });
    if (btn) {
      /* 透镜略放宽，包住被放大（1.26x）的按钮 */
      const m = metrics(btn);
      blob.style.width = Math.max(0, m.w - PAD * 2 + 10) + 'px';
    }
  }

  function clearHover() {
    hovered = null;
    buttons.forEach(b => b.classList.remove('drag-over', 'dim', 'holding'));
  }

  function buzz(ms) {
    try { navigator.vibrate && navigator.vibrate(ms); } catch (_) {}
  }

  dock.addEventListener('pointerdown', e => {
    if (e.button != null && e.button !== 0) return;
    const btn = e.target.closest('[data-nav]');
    if (!btn) return;
    pointerId = e.pointerId;
    startX = e.clientX;
    startY = e.clientY;
    dragging = false;
    hovered = null;
    try { dock.setPointerCapture(pointerId); } catch (_) {}
    /* 按下即成为焦点：透镜先吸附到按下的按钮，按钮同时放大上浮（holding）；拖动时透镜再跟手 */
    btn.classList.add('holding');
    paint(btn, true);
  }, { passive: true });

  dock.addEventListener('pointermove', e => {
    if (pointerId === null || e.pointerId !== pointerId) return;
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    if (!dragging) {
      if (Math.hypot(dx, dy) < DRAG_SLOP) return;
      dragging = true;
      dock.classList.add('dragging');
      blob.style.transition = 'background .2s ease';
      buzz(8);
    }
    follow(e.clientX);
    /* 命中检测：透镜 pointer-events:none，所以 elementFromPoint 拿到的是按钮 */
    const hit = document.elementFromPoint(e.clientX, e.clientY);
    const btn = hit ? hit.closest('#dock [data-nav]') : null;
    setHovered(btn || null);
  }, { passive: true });

  function finish(e, cancelled) {
    if (pointerId === null || (e && e.pointerId !== pointerId)) return;
    const pid = pointerId;
    const wasDragging = dragging;
    const target = hovered;
    pointerId = null;
    dragging = false;
    dock.classList.remove('dragging');
    blob.style.transition = '';
    clearHover();
    try { dock.releasePointerCapture(pid); } catch (_) {}

    if (wasDragging) {
      /* 先把手势触发的导航发出去（click 同步派发，此时还不能吞），
       * 然后再挂上「吞 click」窗口，把浏览器在 pointerup 后补发的那次点击吃掉，
       * 否则原按钮会再导航一次。 */
      if (target && !cancelled && target !== current()) {
        buzz(6);
        target.click();               // 交给 app.js 的 tapDock 处理，含双击回顶逻辑
      }
      suppressClickUntil = performance.now() + 350;
    } else {
      suppressClickUntil = 0;
    }
    animate();                        // 无论成败都弹回合法焦点
  }

  dock.addEventListener('pointerup', e => finish(e, false));
  dock.addEventListener('pointercancel', e => finish(e, true));
  dock.addEventListener('lostpointercapture', e => { if (pointerId !== null) finish(e, true); });

  /* 拖动后吞掉浏览器补发的那次 click（app.js 的导航监听在 document 上） */
  dock.addEventListener('click', e => {
    if (performance.now() < suppressClickUntil) {
      e.stopPropagation();
      e.preventDefault();
    }
  }, true);

  /* active 类由 app.js 切换，这里监听变化同步透镜位置 */
  const mo = new MutationObserver(records => {
    if (dragging) return;
    for (const r of records) {
      if (r.target.classList && r.target.classList.contains('active')) { animate(); return; }
    }
    animate();
  });
  buttons.forEach(b => mo.observe(b, { attributes: true, attributeFilter: ['class'] }));

  window.addEventListener('resize', () => paint(current(), false));
  window.addEventListener('orientationchange', () => {
    setTimeout(() => paint(current(), false), 260);
  });

  /* 首帧定位（不加动画） */
  const boot = () => paint(current(), false);
  if (document.readyState === 'complete') boot();
  else window.addEventListener('load', boot, { once: true });
  boot();
})();
