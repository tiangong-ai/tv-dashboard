(() => {
  'use strict';
  // Correct the known 1920x1080 dashboard only. The measured viewport excludes
  // overflow from its transformed canvas, unlike innerWidth in newer Gecko.
  if (!location.pathname.endsWith('/display/') ||
      !document.getElementById('stage') || !document.getElementById('clock') ||
      !document.getElementById('gpu-groups')) return;
  const fit = () => {
    const root = document.documentElement;
    if (root.clientWidth > 0 && root.clientHeight > 0) {
      root.style.setProperty('--scale', String(Math.min(root.clientWidth / 1920, root.clientHeight / 1080)));
    }
  };
  // Run after the page's own resize handlers, which use innerWidth/innerHeight.
  window.addEventListener('resize', () => requestAnimationFrame(fit));
  window.addEventListener('pageshow', () => requestAnimationFrame(fit));
  requestAnimationFrame(fit);
})();
