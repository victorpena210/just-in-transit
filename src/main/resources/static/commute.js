(() => {
  'use strict';
  const form = document.getElementById('commute-form');
  if (!form) return;
  let dirty = false, pending = false;
  let received = Date.now();
  let checked = Number(document.getElementById('result')?.dataset.checked) || received;
  const status = text => { const el = document.getElementById('refresh-status'); if (el) el.textContent = text; };
  form.addEventListener('input', () => {
    dirty = true;
    document.getElementById('result')?.classList.add('stale');
    status('Arrival settings changed. Press Find my departure to update the plan.');
    const countdown = document.getElementById('departure-countdown');
    if (countdown) countdown.textContent = 'Previous plan — submit your new arrival settings.';
  });
  function tick() {
    const result = document.getElementById('result');
    const output = document.getElementById('departure-countdown');
    if (!output || !result?.dataset.leave || dirty) return;
    if (Date.now() - received > 90000) {
      output.textContent = 'Timing needs another check. Reconnect and refresh.';
      result.classList.add('stale');
      return;
    }
    const left = Number(result.dataset.leave) - (checked + Date.now() - received);
    if (left < 0) output.textContent = 'Your leave-home time has arrived. Check again before leaving.';
    else if (left < 60000) output.textContent = 'Leave in less than a minute.';
    else {
      const minutes = Math.ceil(left / 60000);
      output.textContent = minutes >= 60
        ? `Leave in ${Math.floor(minutes / 60)} hr ${minutes % 60} min.`
        : `Leave in ${minutes} min.`;
    }
  }
  async function refresh() {
    if (pending || dirty || document.hidden || !form.checkValidity()) return;
    const old = document.getElementById('result');
    if (old?.dataset.calculated !== 'true') return;
    pending = true;
    status('Checking the connection…');
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 45000);
    try {
      const query = new URLSearchParams();
      ['date', 'arriveBy', 'finalWalk'].forEach(name => query.set(name, form.elements[name].value));
      const response = await fetch(`${form.dataset.previewUrl}?${query}`, {cache:'no-store', signal:controller.signal});
      if (!response.ok || response.redirected) throw new Error('Unavailable');
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      const replacement = doc.getElementById('result');
      if (!replacement || replacement.dataset.calculated !== 'true') throw new Error('Invalid response');
      if (dirty) return; // Never install a response for settings edited while the request was in flight.
      old.replaceWith(replacement);
      checked = Number(replacement.dataset.checked); received = Date.now();
      tick();
    } catch (_) {
      old?.classList.add('stale');
      status('Could not refresh. Times shown are from the last check; reconnect and try again.');
      const output = document.getElementById('departure-countdown');
      if (output) output.textContent = 'Update unavailable — check the connection before leaving.';
      received = Date.now() - 91000;
    } finally { clearTimeout(timeout); pending = false; }
  }
  document.addEventListener('click', e => { if (e.target.closest('#refresh-plan')) refresh(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { tick(); refresh(); } });
  window.addEventListener('online', refresh);
  setInterval(refresh, 30000); setInterval(tick, 1000); tick();
  // Replace any previously installed offline shell; it must not suggest starting the removed trip workflow.
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
})();
