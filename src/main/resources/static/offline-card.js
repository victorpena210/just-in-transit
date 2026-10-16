(() => {
  const KEY = 'jit-offline-card-v1', PREF = 'jit-offline-enabled';
  const toggle = document.getElementById('keep-offline'), status = document.getElementById('offline-status');
  const say = text => { if (status) status.textContent = text; };
  let enabled = false, pending = false, generation = 0;
  try { enabled = localStorage.getItem(PREF) === 'yes'; } catch (_) {}
  if (toggle) toggle.checked = enabled;
  function clear() { generation++;enabled = false;try { localStorage.removeItem(KEY);localStorage.removeItem(PREF); } catch (_) {}if (toggle) toggle.checked = false; }
  async function save() {
    if (!enabled || pending || document.hidden) return;
    pending = true;const version = generation;
    try {
      if (!window.isSecureContext || !('serviceWorker' in navigator)) throw new Error('Offline saving needs HTTPS or localhost in a supported browser.');
      await navigator.serviceWorker.register('/sw.js');await navigator.serviceWorker.ready;
      const response = await fetch('/journey/offline', {credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(15000)});
      if (response.redirected || response.status === 401 || response.status === 403) { clear();throw new Error('Sign in again to save an offline card.'); }
      if (!response.ok) throw new Error('Could not update the card. The last saved version remains unchanged.');
      const card = await response.json();if (!enabled || version !== generation) return;
      if (!card.available) { localStorage.removeItem(KEY);say('Start or resume a trip to save its offline card.');return; }
      localStorage.setItem(KEY, JSON.stringify(card));localStorage.setItem(PREF, 'yes');say('Card saved on this device. It shows the last estimates, never a live countdown.');
    } catch (error) { say(error.message || 'Offline saving is unavailable in this browser.'); }
    finally { pending = false; }
  }
  toggle?.addEventListener('change', () => { if (toggle.checked) { enabled = true;try { localStorage.setItem(PREF, 'yes'); } catch (_) {}save(); } else { clear();say('Offline card deleted from this device.'); } });
  document.addEventListener('submit', event => {
    const form = event.target, path = new URL(form.getAttribute('action') || location.href, location.href).pathname;
    const action = event.submitter?.value || new FormData(form).get('action');
    if (path === '/logout' || path === '/forget' || path === '/journey/resume' && action === 'discard') { clear();try { localStorage.removeItem('jit-trip-view-v1'); } catch (_) {} }
  });
  window.addEventListener('storage', event => { if (event.key === PREF) { generation++;enabled = event.newValue === 'yes';if (toggle) toggle.checked = enabled; } });
  window.addEventListener('online', save);document.addEventListener('visibilitychange', () => { if (!document.hidden) save(); });
  setInterval(save, 30000);save();
})();
