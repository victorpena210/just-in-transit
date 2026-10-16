/* Durable, browser-scoped outbox. No coordinates, credentials or addresses are stored here. */
(() => {
  const root = document.getElementById('trip-sync-root'); if (!root) return;
  const $ = id => document.getElementById(id), scope = root.dataset.scope;
  const KEY = 'jit-trip-updates-v1:' + scope, PREF = 'jit-history-v1:' + scope;
  let state = { scope, journeyId: root.dataset.journeyId || null, revision: root.dataset.revision || null, phase: root.dataset.phase || null, expiresAt: root.dataset.expiresAt || null }, busy = false, blocked = false;
  let serverTime = Date.parse(root.dataset.now), received = performance.now();
  const now = () => new Date(serverTime + performance.now() - received).toISOString();
  const phases = ['WALKING_TO_24', 'WAITING_FOR_24', 'ON_24', 'TRANSFERRING', 'WAITING_FOR_93', 'ON_93', 'WALKING_TO_DESTINATION', 'ARRIVED'];
  const first = root.dataset.firstRoute, second = root.dataset.secondRoute;
  const labels = [`Walking to the ${first}`, `Waiting for the ${first}`, `On the ${first}`, `Walking to the ${second} stop`, `Waiting for the ${second}`, `On the ${second}`, 'Walking to your destination', "You've arrived"];
  const nextLabels = [`I'm on the ${first}`, `I'm on the ${first}`, `I'm off the ${first}`, `I'm at the ${second} stop`, `I'm on the ${second}`, `I'm off the ${second}`, "I've arrived", ''];
  function read() { try { const q = JSON.parse(localStorage.getItem(KEY) || '[]'); return Array.isArray(q) ? q : []; } catch (_) { return []; } }
  const locked = (name, operation) => navigator.locks ? navigator.locks.request(name + ':' + scope, operation) : operation();
  function write(queue) { localStorage.setItem(KEY, JSON.stringify(queue)); }
  function say(title, detail, failed = false) {
    $('trip-save-status').textContent = title; $('trip-save-detail').textContent = detail;
    $('trip-save-panel').classList.toggle('save-warning', failed);
    $('trip-retry').hidden = !read().length;
    $('trip-discard-pending').hidden = !blocked;
  }
  function projection() {
    const queued = read();
    let phase = queued.length ? queued[0].expectedPhase : state.phase;
    for (const item of queued) {
      if (item.action === 'selectBus') phase = item.route === first ? 'ON_24' : 'ON_93';
      else if (item.action === 'advance') phase = phase === 'WALKING_TO_24' ? 'ON_24' : phases[phases.indexOf(phase) + 1];
      else if (item.action === 'at24') phase = 'WAITING_FOR_24';
      else if (item.action === 'end') phase = null;
    }
    return phase;
  }
  function renderPending() {
    const queue = read(); if (!queue.length) return;
    const phase = projection(), title = labels[phases.indexOf(phase)] || 'Trip ended';
    say(blocked ? 'Update needs review' : 'Waiting to sync · ' + title, blocked ? 'Your unsaved updates are still on this phone. Review the saved trip before discarding them.' : `${queue.length} update${queue.length === 1 ? '' : 's'} saved on this phone. Server confirmation is pending.`, true);
    saveOfflineView(phase, queue.length);
    if ($('journey-title')) $('journey-title').textContent = title + ' · waiting to sync';
    const form = document.querySelector('.progress-form');
    if (!form) {
      let button = $('pending-progress');
      if (!button) { button = document.createElement('button'); button.id = 'pending-progress'; button.className = 'primary'; button.type = 'button'; button.addEventListener('click', () => enqueue(projection() === 'ARRIVED' ? 'end' : 'advance')); $('trip-save-panel').append(button); }
      button.hidden = !phase; button.textContent = phase === 'ARRIVED' ? 'Finish commute' : nextLabels[phases.indexOf(phase)] || '';
    }
    if (form) { form.hidden = !phase || phase === 'ARRIVED'; form.querySelector('button').textContent = nextLabels[phases.indexOf(phase)] || 'Saved locally'; }
    // Prevent stale predictions from looking like a newly saved trip.
    document.querySelectorAll('.active-arrival, #journey-guidance, .active-timeline').forEach(el => el.hidden = true);
    document.querySelectorAll('#active-journey form:not(.progress-form):not(.end-journey)').forEach(el => el.hidden = true);
  }
  function saveOfflineView(phase = state.phase, pending = read().length) {
    try { localStorage.setItem('jit-trip-view-v1', JSON.stringify({ scope, phase, label: labels[phases.indexOf(phase)] || 'Trip ended', pending, expiresAt: state.expiresAt || new Date(serverTime + 18 * 3600000).toISOString(), checkedAt: now() })); } catch (_) {}
  }
  async function status() {
    const response = await fetch('/journey/status', { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(15000) });
    if (!response.ok || response.redirected) throw new Error('Could not reconnect to your saved trip.');
    const value = await response.json();
    if (value.scope !== scope) { blocked = true; throw new Error('This browser identity changed. Your old updates will not be sent to a different profile.'); }
    serverTime = Date.parse(value.now); received = performance.now(); return value;
  }
  async function flush() {
    if (busy || blocked || !read().length || !navigator.onLine || document.hidden) return;
    busy = true;
    try {
      await locked('jit-trip-send', async () => {
      let current = await status();
      while (read().length) {
        const item = read()[0];
        const response = await fetch('/journey/sync', { method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: { 'Content-Type': 'application/json', [current.csrfHeader]: current.csrf }, body: JSON.stringify(Object.fromEntries(Object.entries(item).filter(([key]) => key !== 'parentEventId'))), signal: AbortSignal.timeout(20000) });
        if (!response.ok || response.redirected) {
          let message = 'Save failed. Your update remains on this phone; reconnect or retry.';
          try { message = (await response.json()).message || message; } catch (_) {}
          if ([400, 409].includes(response.status)) blocked = true;
          throw new Error(message);
        }
        current = await response.json();
        if (current.scope !== scope) { blocked = true; throw new Error('Browser identity changed. Review your trip.'); }
        // Another tab may already have acknowledged this same event. Remove by ID.
        await locked('jit-trip-write', () => {
        const queue = read().filter(entry => entry.eventId !== item.eventId);
        if (queue.length && queue[0].parentEventId === item.eventId) {
          queue[0].journeyId = current.journeyId; queue[0].revision = current.revision; delete queue[0].parentEventId;
        }
        write(queue); state = current; saveOfflineView();
        });
      }
      say('Saved', 'Your trip has been saved.'); window.location.replace('/');
      });
    } catch (error) {
      say(blocked ? 'Update needs review' : 'Waiting to sync', error.message || 'Your update remains on this phone.', true);
    } finally { busy = false; $('trip-discard-pending').hidden = !blocked; }
  }
  async function enqueue(action, extra = {}) {
    await locked('jit-trip-write', () => {
    if (blocked) { say('Review the pending update first', 'Retry it or discard unsaved updates to reload the saved trip.', true); return; }
    const queue = read();
    if (queue.length >= 40) { say('Too many unsaved updates', 'Reconnect and sync before recording more steps.', true); return; }
    if (queue.length && !['advance', 'at24', 'end'].includes(action)) { say('Save pending', 'Sync the pending step before changing buses or settings.', true); return; }
    const phase = projection();
    if (action !== 'selectBus' && !phase) { say('No active trip', 'Select the bus you are on or start a trip first.', true); return; }
    const event = { eventId: crypto.randomUUID(), journeyId: state.journeyId, revision: state.revision, action, recordedAt: now(), expectedPhase: phase, corridor: root.dataset.corridor, ...extra };
    if (queue.length) event.parentEventId = queue.at(-1).eventId;
    try { write([...queue, event]); } catch (_) { say('Not saved', 'This browser could not store the update. Keep this page open and enable browser storage before retrying.', true); return; }
    renderPending();
    });
    flush();
  }
  // parentEventId is local bookkeeping and is removed before sending.
  function hydrate(element) {
    if (!element || read().length || element.dataset.scope !== scope) return;
    state = { scope, journeyId: element.dataset.journeyId || null, revision: element.dataset.revision || null, phase: element.dataset.phase || null, expiresAt: element.dataset.expiresAt || null };
    serverTime = Date.parse(element.dataset.now); received = performance.now(); saveOfflineView();
  }
  window.JitTrip = { hydrate, hasPending: () => read().length > 0, selectBus: (route, vehicleId, driverConfirmed, keepHistory) => enqueue('selectBus', { route, vehicleId, driverConfirmed, keepHistory }), enqueue };
  document.addEventListener('submit', event => {
    const form = event.target;
    if (form.id === 'trip-destination-form') { event.preventDefault(); enqueue('destination', { destinationStop: $('trip-destination-stop').value, walkToWork: Number($('trip-final-walk').value) }); return; }
    if (new URL(form.action, location.href).pathname !== '/journey/action') return;
    event.preventDefault();
    const action = event.submitter?.name === 'action' ? event.submitter.value : new FormData(form).get('action');
    if (!['advance', 'at24', 'end'].includes(action) && (!navigator.onLine || read().length)) { say('Reconnect for this action', 'Your confirmed progress and pending updates are kept.', true); return; }
    enqueue(action);
  });
  document.addEventListener('change', event => { if (event.target.id !== 'trip-history-setting') return; try { localStorage.setItem(PREF, event.target.checked ? 'yes' : 'no'); } catch (_) {} enqueue('history', { keepHistory: event.target.checked }); });
  document.querySelectorAll('[data-history-preference]').forEach(input => {
    try { input.checked = localStorage.getItem(PREF) === 'yes'; } catch (_) {}
    input.addEventListener('change', () => { try { localStorage.setItem(PREF, input.checked ? 'yes' : 'no'); } catch (_) {} });
  });
  $('trip-retry').addEventListener('click', () => { blocked = false; flush(); });
  $('trip-discard-pending').addEventListener('click', () => { if (confirm('Discard unsaved updates on this phone and reload the last server-saved trip?')) { write([]); location.replace('/'); } });
  window.addEventListener('online', flush); document.addEventListener('visibilitychange', () => { if (!document.hidden) flush(); });
  window.addEventListener('storage', event => { if (event.key === KEY) { renderPending(); if (!read().length) location.replace('/'); } });
  if (isSecureContext && 'serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  if (!read().length) saveOfflineView();
  setInterval(flush, 15000); renderPending(); flush();
})();
