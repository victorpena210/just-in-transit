(() => {
  const KEY = 'jit-offline-card-v1', target = document.getElementById('offline-card');
  const add = (tag, text, parent = target) => { const el = document.createElement(tag); el.textContent = text; parent.append(el); return el; };
  function render() {
    target.replaceChildren();
    try {
      const trip = JSON.parse(localStorage.getItem('jit-trip-view-v1'));
      if (trip && Date.now() < Date.parse(trip.expiresAt)) {
        const panel = add('section', ''); add('h2', trip.label, panel);
        add('p', trip.pending ? trip.pending + ' update(s) waiting to sync from this phone.' : 'Last saved trip status. Live location and arrival estimates are unavailable offline.', panel);
        add('p', 'Reconnect to resume your trip and sync pending updates. Your selected bus is kept.', panel);
      }
    } catch (_) {}
    let card;try { card = JSON.parse(localStorage.getItem(KEY)); } catch (_) {}
    if (!card?.available || !Number.isFinite(Date.parse(card.expiresAt)) || Date.now() >= Date.parse(card.expiresAt)) {
      try { localStorage.removeItem(KEY); } catch (_) {}
      add('h2', 'No saved trip card');add('p', 'Reconnect, start or resume a trip, and choose Keep an offline card on this device.');return;
    }
    add('h2', card.title);add('p', card.phase);add('strong', card.instruction);
    add('p', 'Saved '+new Date(card.checkedAt).toLocaleString('en-US', {timeZone: 'America/Chicago'})+' Central. All estimates below are from that check.');
    for (const line of card.lines) { const row = add('div', '');row.className = 'offline-stop';add('h3', line.label+' · '+line.stop, row);add('p', line.estimate+' · '+line.source, row); }
    for (const note of card.notes) add('p', note);
  }
  document.getElementById('clear-offline').addEventListener('click', () => { try { localStorage.removeItem(KEY);localStorage.removeItem('jit-offline-enabled'); } catch (_) {} render(); });
  window.addEventListener('online', () => location.replace('/'));
  render();setInterval(render, 60000);
})();
