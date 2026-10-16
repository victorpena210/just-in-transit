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
  for (const id of ['commute-form', 'daily-form']) {
    const form = $(id); if (!form) continue;
    const mode = form.querySelector('[name="planMode"]');
    const updateWindow = () => {
      const departing = mode?.value === 'depart';
      form.querySelectorAll('[data-arrival-window]').forEach(el => el.hidden = departing);
      const label = form.querySelector('[data-time-label]');
      if (label) label.textContent = departing ? 'Leave starting point after (Central)' : 'Be at destination by (Central)';
    };
    mode?.addEventListener('change', updateWindow);updateWindow();
  }
  if (!card) return;
  let snapshot = null, received = 0, failed = false, dirty = false, pending = false, expired = false, changedForm = null;
  const token = card.dataset.token;
  const starts = () => document.querySelectorAll('.start-form button');
  const setText = (selector, value) => { for (const element of card.querySelectorAll(selector)) element.textContent = value; };
  function tick() {
    const now = snapshot ? Date.parse(snapshot.now) + performance.now() - received : Date.now();
    const clock = snapshot?.countdowns.find(item => item.id === (card.dataset.startMode === 'stop' ? 'bus24' : card.dataset.startMode === 'onboard' ? 'transfer' : 'leave'));
    const stale = !clock || failed || dirty || now > Date.parse(clock.validUntil);
    if (stale || !clock.target) {
      $('leave-heading').textContent = dirty ? 'Settings changed' : expired ? 'Plan expired or replaced' : 'Departure estimate';
      $('leave-countdown').textContent = dirty || expired ? 'Update plan' : 'Unconfirmed';
      $('leave-basis').textContent = dirty ? 'Submit your changes to find a new departure.' : expired ? 'Find fresh departures to restore this plan.' : 'Refresh the recommendation to check the current times.';
    } else {
      const seconds = Math.ceil((Date.parse(clock.target) - now) / 1000);
      const joining = card.dataset.startMode !== 'home';
      $('leave-heading').textContent = joining ? clock.label : seconds > 60 ? 'Leave in' : seconds >= 0 ? 'Time to leave' : 'Leave time passed';
      const minutes = Math.ceil(seconds / 60);
      $('leave-countdown').textContent = seconds <= 0 ? (joining ? 'Check current bus' : seconds === 0 ? 'Leave now' : 'Choose your stage below') : seconds <= 60 ? (joining ? 'Under a minute' : 'Leave now') : minutes >= 60 ? `${Math.floor(minutes / 60)}h ${minutes % 60}m` : `${minutes} minutes`;
      $('leave-basis').textContent = clock.source;
    }
    setText('[data-estimate-state]', dirty ? 'Settings changed. Times below belong to the previous plan.' : stale ? 'Displayed times are the last estimates; they are not currently confirmed.' : 'Checked ' + new Intl.DateTimeFormat('en-US', { timeZone: 'America/Chicago', hour: 'numeric', minute: '2-digit' }).format(new Date(snapshot.now)) + ' Central · estimates may change.');
    if (expired) starts().forEach(button => button.disabled = true);
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
      expired = Boolean(data.planExpired);
      if (!data.decision || !Array.isArray(data.countdowns) || !data.now) throw new Error('Plan expired');
      snapshot = data; received = performance.now(); failed = false;
      const d = data.decision;
      setText('[data-decision-leave]', d.leave);setText('[data-decision-work]', d.work);
      setText('[data-decision-deadline]', d.deadline);setText('[data-decision-transfer]', `${d.transfer} min waiting after the transfer walk`);
      setText('[data-first-board]', d.firstBoard);setText('[data-transfer-arrival]', d.transferArrival);setText('[data-second-board]', d.secondBoard);setText('[data-first-wait]', d.firstWait);
      setText('[data-decision-backup]', d.backup);setText('[data-decision-confidence]', d.confidence);setText('.badge', d.rating);
      setText('[data-timing-board-first]',d.firstBoard);setText('[data-timing-off-first]',d.firstAlight);
      setText('[data-timing-board-second]',d.secondBoard);setText('[data-timing-off-second]',d.secondAlight);
      if (d.timing) {
        setText('[data-timing-stop]', d.timing.atFirstStop);setText('[data-timing-first]', `${d.timing.firstWait} min`);
        setText('[data-timing-transfer]', `${d.timing.transferWait} min`);setText('[data-timing-total]', `${d.timing.totalMinutes} min`);
        setText('[data-timing-traffic]', d.timing.trafficNote);
        setText('[data-preference-note]', d.timing.preferenceNote);
        if (!d.timing.fitsWindows) {
          setText('.option-label', 'Outside preferences · update plan');
          card.classList.add('tight');
        }
      }
      if (d.transfer < 0) { $('leave-basis').textContent = 'Connection no longer fits. Update the plan.'; failed = true; }
    } catch (_) { failed = true; }
    finally { pending = false; tick(); }
  }
  const refreshButton = document.createElement('button');refreshButton.type = 'button';refreshButton.className = 'text-button';
  refreshButton.textContent = 'Refresh recommendation ↻';refreshButton.addEventListener('click', () => expired ? replan() : refresh());card.querySelector('.departure-decision').append(refreshButton);
  function replan() {
    const form = changedForm || $('commute-form');
    const submit = form?.id === 'daily-form' ? form.querySelector('button[type="submit"]:not([name="refreshTrips"])') : $('plan-button');
    if (form && form.reportValidity()) form.requestSubmit(submit);
  }
  const newPlan = document.createElement('button');newPlan.type = 'button';newPlan.className = 'secondary';
  newPlan.textContent = 'Find fresh departures';newPlan.addEventListener('click', replan);
  card.querySelector('.departure-decision').append(newPlan);
  const invalidate = event => {
    if (event.target.closest('.stop-finder')) return;
    dirty = true; changedForm = event.target.closest('form') || $('commute-form'); starts().forEach(button => button.disabled = true); tick();
  };
  for (const id of ['commute-form', 'daily-form']) for (const type of ['input', 'change']) $(id)?.addEventListener(type, invalidate);
  document.addEventListener('jit:point-resolved', () => { dirty = true; changedForm = $('commute-form'); starts().forEach(button => button.disabled = true);tick(); });
  document.addEventListener('visibilitychange', () => { tick();if (!document.hidden) refresh(); });
  window.addEventListener('offline', () => { failed = true;tick(); });
  window.addEventListener('online', refresh);
  setInterval(tick, 1000);setInterval(refresh, 30000);refresh();
})();
