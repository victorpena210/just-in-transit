(() => {
  const $ = id => document.getElementById(id);
  const card = $('recommendation');
  const compare = $('compare-departures');
  if (compare && card) {
    for (const option of document.querySelectorAll('[data-option-index]')) {
      if (option.dataset.optionIndex !== '0') $('alternatives').append(option);
    }
    card.after(compare);
  }
  // Event delegation also covers links inside live-refreshed content.
  document.addEventListener('click', event => {
    if (event.target.closest('[data-edit-commute]') && $('edit-commute')) $('edit-commute').open = true;
  });
  if (!card) return;
  let snapshot = null, received = 0, failed = false, dirty = false, pending = false;
  const token = card.dataset.token;
  const starts = () => document.querySelectorAll('.start-form button');
  const setText = (selector, value) => { const element = card.querySelector(selector); if (element) element.textContent = value; };
  function tick() {
    const now = snapshot ? Date.parse(snapshot.now) + performance.now() - received : Date.now();
    const clock = snapshot?.countdowns.find(item => item.id === (card.dataset.startMode === 'stop' ? 'bus24' : card.dataset.startMode === 'onboard' ? 'transfer' : 'leave'));
    const stale = !clock || failed || dirty || now > Date.parse(clock.validUntil);
    if (stale || !clock.target) {
      $('leave-heading').textContent = dirty ? 'Settings changed' : 'Departure estimate';
      $('leave-countdown').textContent = dirty ? 'Update plan' : 'Unconfirmed';
      $('leave-basis').textContent = dirty ? 'Submit your changes to find a new departure.' : 'Refresh the recommendation to check the current times.';
    } else {
      const seconds = Math.ceil((Date.parse(clock.target) - now) / 1000);
      const joining = card.dataset.startMode !== 'home';
      $('leave-heading').textContent = joining ? clock.label : seconds > 60 ? 'Leave in' : seconds >= 0 ? 'Time to leave' : 'Leave time passed';
      const minutes = Math.ceil(seconds / 60);
      $('leave-countdown').textContent = seconds <= 0 ? (joining ? 'Check current bus' : seconds === 0 ? 'Leave now' : 'Choose your stage below') : seconds <= 60 ? (joining ? 'Under a minute' : 'Leave now') : minutes >= 60 ? `${Math.floor(minutes / 60)}h ${minutes % 60}m` : `${minutes} minutes`;
      $('leave-basis').textContent = clock.source;
    }
    // Starting always revalidates the selected buses on the server as well.
    card.querySelectorAll('.start-form button').forEach(button => button.disabled = stale || !clock?.target || now > Date.parse(clock.target));
  }
  async function refresh() {
    if (dirty || pending || document.hidden) return;
    pending = true;
    try {
      const response = await fetch('/maps/live?' + new URLSearchParams({ token, option: '0' }), { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(15000) });
      if (!response.ok || response.redirected) throw new Error('Unavailable');
      const data = await response.json();
      if (dirty) return;
      if (!data.decision || !Array.isArray(data.countdowns) || !data.now) throw new Error('Plan expired');
      snapshot = data; received = performance.now(); failed = false;
      const d = data.decision;
      setText('[data-decision-leave]', d.leave);setText('[data-decision-work]', d.work);
      setText('[data-decision-deadline]', d.deadline);setText('[data-decision-transfer]', `${d.transfer} min after the transfer walk`);
      setText('[data-decision-backup]', d.backup);setText('[data-decision-confidence]', d.confidence);setText('.badge', d.rating);
      if (d.transfer < 0) { $('leave-basis').textContent = 'Connection no longer fits. Update the plan.'; failed = true; }
    } catch (_) { failed = true; }
    finally { pending = false; tick(); }
  }
  const refreshButton = document.createElement('button');refreshButton.type = 'button';refreshButton.className = 'text-button';
  refreshButton.textContent = 'Refresh recommendation ↻';refreshButton.addEventListener('click', refresh);card.querySelector('.departure-decision').append(refreshButton);
  const invalidate = event => {
    if (event.target.closest('.stop-finder')) return;
    dirty = true; starts().forEach(button => button.disabled = true); tick();
  };
  for (const id of ['commute-form', 'daily-form']) for (const type of ['input', 'change']) $(id)?.addEventListener(type, invalidate);
  document.addEventListener('jit:point-resolved', () => { dirty = true; starts().forEach(button => button.disabled = true);tick(); });
  document.addEventListener('visibilitychange', () => { tick();if (!document.hidden) refresh(); });
  window.addEventListener('offline', () => { failed = true;tick(); });
  window.addEventListener('online', refresh);
  setInterval(tick, 1000);setInterval(refresh, 30000);refresh();
})();
