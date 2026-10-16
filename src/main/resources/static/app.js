// Active journeys refresh independently of the setup form and never POST /plan.
(() => {
  if (document.getElementById('live-map-panel')) return; // Map refresh preserves the map and location watch.
  const toggle = document.getElementById('journey-refresh');
  if (!toggle) return;
  const status = document.getElementById('journey-refresh-status');
  try { toggle.checked = sessionStorage.getItem('jit-journey-refresh') !== 'false'; } catch (_) {}
  toggle.addEventListener('change', () => {
    try { sessionStorage.setItem('jit-journey-refresh', String(toggle.checked)); } catch (_) {}
    status.textContent = toggle.checked ? 'Refreshing while this page is visible.' : 'Automatic refresh paused. Use Refresh now for current estimates.';
  });
  const refreshed = Date.now();
  function refresh() {
    if (document.hidden || !toggle.checked || window.JitTrip?.hasPending()) return;
    if (!navigator.onLine) { status.textContent = 'Offline. These times may be out of date. Your journey is kept in this session.'; return; }
    if (Date.now() - refreshed < 25000) return;
    const focused = document.activeElement;
    if (focused && focused.closest('form')) return;
    window.location.replace('/');
  }
  setInterval(refresh, 30000);
  document.addEventListener('visibilitychange', refresh);
  window.addEventListener('online', refresh);
})();

(() => {
  const form = document.querySelector('#commute-form');
  if (!form) return;
  const field = id => document.getElementById(id);
  document.querySelectorAll('[data-edit-commute]').forEach(link => link.addEventListener('click', () => { field('edit-commute').open = true; }));
  const kinds = ['origin', 'destination'];
  const versions = { origin: 0, destination: 0 };
  const stopVersions = { origin: 0, destination: 0 };
  let pending = 0;
  let dirty = false;
  form.addEventListener('input', () => { dirty = true; });
  form.addEventListener('change', () => { dirty = true; });
  const walkField = kind => field(kind === 'origin' ? 'walkToStop' : 'walkToWork');
  const resolvedField = kind => field(kind === 'origin' ? 'resolvedOrigin' : 'resolvedDestination');
  const addressMode = kind => ['address', kind].includes(field('locationMode').value);
  const originalStops = Object.fromEntries(kinds.map(kind => [kind, [...field(kind + 'Stop').options].map(o => ({ label: o.text, value: o.value }))]));
  const originalSelection = Object.fromEntries(kinds.map(kind => [kind, field(kind + 'Stop').value]));
  const searchAreas = {};
  field('location-mode-control').hidden = true;
  function setAddressMode(kind, enabled) {
    const other = kind === 'origin' ? 'destination' : 'origin', otherEnabled = addressMode(other);
    field('locationMode').value = enabled ? (otherEnabled ? 'address' : kind) : (otherEnabled ? other : 'manual');
    kinds.forEach(validity);
    updateMethod(kind);
  }
  function updateMethod(kind) {
    field(kind + '-method').textContent = addressMode(kind)
      ? (point(kind) ? 'Map point selected · choose your stop and check the walking minutes.' : 'Location not confirmed yet · set your entrance on the map.')
      : 'Using the selected stop and your walking minutes. Address search is optional.';
    field(kind + '-step').querySelector('.manual-switch').hidden = !addressMode(kind);
  }
  function fallback(kind, message) {
    field(kind + '-fallback-message').textContent = message;
    field(kind + '-fallback').hidden = false;
  }
  function manual(kind, selectedStop) {
    versions[kind]++; dirty = true;
    const old = selectedStop || field(kind + 'Stop').value || originalSelection[kind];
    setAddressMode(kind, false);
    field(kind + 'Lat').value = ''; field(kind + 'Lon').value = ''; resolvedField(kind).value = '';
    field(kind + '-results').replaceChildren(); field(kind + '-fallback').hidden = true;
    field(kind + '-location-note').textContent = ''; field(kind + '-location-map').hidden = true;
    const select = field(kind + 'Stop');
    // Restore all server-provided choices; date/direction are checked again on submission.
    const extra = [...select.options].filter(o => o.value && !originalStops[kind].some(s => s.value === o.value));
    select.replaceChildren(...originalStops[kind].map(o => new Option(o.label, o.value)), ...extra.map(o => new Option(o.text, o.value)));
    select.value = old; // Keep checked walking minutes when changing only the input method.
    if (!field(kind).value.trim()) field(kind).value = kind === 'origin' ? 'My starting point' : 'My destination';
    validity(kind); updateMethod(kind);
    status(kind, 'Check the stop, then enter your walking minutes. No address lookup is needed.');
    document.dispatchEvent(new CustomEvent('jit:location-cleared', { detail: { kind } }));
    walkField(kind).focus();
  }
  document.querySelectorAll('[data-manual]').forEach(button => button.addEventListener('click', () => manual(button.dataset.manual)));
  document.querySelectorAll('[data-search-pin]').forEach(button => button.addEventListener('click', () => {
    const kind = button.dataset.searchPin;
    document.dispatchEvent(new CustomEvent('jit:pin-request', { detail: { kind, area: searchAreas[kind] } }));
  }));
  const point = kind => field(kind + 'Lat').value !== '' && field(kind + 'Lon').value !== ''
    ? { lat: Number(field(kind + 'Lat').value), lon: Number(field(kind + 'Lon').value) } : null;
  function status(kind, message, error = false) {
    const target = field(kind + '-status');
    target.textContent = message;
    target.classList.toggle('lookup-error', error);
  }
  function validity(kind) {
    const ok = !addressMode(kind) || (point(kind) && resolvedField(kind).value === field(kind).value);
    field(kind).setCustomValidity(ok ? '' : 'Set this entrance on the map, select an exact address, or use stop and walking minutes.');
  }
  function invalidate(kind) {
    versions[kind]++;
    clearStopSearch(kind);
    field(kind + 'Lat').value = '';
    field(kind + 'Lon').value = '';
    resolvedField(kind).value = '';
    field(kind + '-results').replaceChildren();
    field(kind + '-location-note').textContent = '';
    field(kind + '-location-map').hidden = true;
    field(kind + '-fallback').hidden = true; delete searchAreas[kind];
    if (addressMode(kind)) {
      field(kind + 'Stop').replaceChildren(new Option('Find your location again', ''));
      walkField(kind).value = '';
    }
    validity(kind); updateMethod(kind);
    document.dispatchEvent(new CustomEvent('jit:location-cleared', { detail: { kind } }));
  }
  function clearStopSearch(kind) {
    stopVersions[kind]++;
    field(kind + '-stop-results').replaceChildren();
    field(kind + '-stop-status').textContent = 'Search by stop name, intersection, or stop ID for the selected travel date.';
  }
  function showLocation(kind, label, selected) {
    field(kind + '-location-note').textContent = 'Map point confirmed. You can adjust it with Set on map.';
    const link = field(kind + '-location-map');
    link.href = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(selected.lat + ',' + selected.lon)}`;
    link.hidden = false;
  }
  function meters(a, b) {
    const rad = degrees => degrees * Math.PI / 180;
    const h = Math.sin(rad(b.lat - a.lat) / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(rad(b.lon - a.lon) / 2) ** 2;
    return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
  }
  async function request(path, body) {
    const token = form.querySelector('input[name="_csrf"]');
    pending++;
    try {
      const response = await fetch('/maps/' + path, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token.value },
        body: JSON.stringify({ ...body, corridor: form.elements.corridor.value }), credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(path === 'search' ? 25000 : 15000)
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.message || result.detail || 'Lookup unavailable. Try again or use manual settings.');
      return result;
    } catch (error) {
      if (error.name === 'TimeoutError' || error.name === 'AbortError') throw new Error('The lookup took too long. Set the entrance on the map, or use stop and walking minutes.');
      if (error instanceof TypeError) throw new Error('Could not connect. Set the entrance on the map, or use stop and walking minutes.');
      throw error;
    } finally { pending--; }
  }
  async function nearby(kind, selected, version) {
    // Keep eligible manual stop choices if the nearby-stop lookup fails.
    walkField(kind).value = '';
    const stops = await request('stops', { kind, ...selected, date: field('date').value });
    if (version !== versions[kind] || !addressMode(kind)) return;
    const select = field(kind + 'Stop');
    select.replaceChildren(new Option(stops.length ? 'Choose a nearby stop' : 'No supported nearby stops', ''));
    for (const stop of stops) select.add(new Option(`${stop.name} · stop ${stop.id} · ${stop.straightLineMeters} m straight-line distance`, stop.id));
    status(kind, stops.length
      ? `${stops.length} supported stops within 2 km. Choose one to calculate the walk, or search for a specific stop below.`
      : 'No supported stop within 2 km for this date. Check the selected location, or search by stop name below.', !stops.length);
  }
  async function choose(kind, place) {
    dirty = true; setAddressMode(kind, true);
    const version = ++versions[kind];
    clearStopSearch(kind);
    field(kind).value = place.label;
    resolvedField(kind).value = place.label;
    field(kind + 'Lat').value = place.lat;
    field(kind + 'Lon').value = place.lon;
    field(kind + '-results').replaceChildren(); field(kind + '-fallback').hidden = true;
    validity(kind); updateMethod(kind);
    showLocation(kind, place.label, place);
    document.dispatchEvent(new CustomEvent('jit:point-resolved', { detail: { kind, ...place } }));
    status(kind, 'Finding stops on the supported route…');
    try { await nearby(kind, { lat: place.lat, lon: place.lon }, version); }
    catch (error) { if (version === versions[kind]) status(kind, error.message, true); }
  }
  document.addEventListener('jit:location-selected', event => {
    const place = event.detail;
    if (!kinds.includes(place.kind) || !Number.isFinite(place.lat) || !Number.isFinite(place.lon)
        || place.lat < 29.1 || place.lat > 29.9 || place.lon < -99.1 || place.lon > -98) return;
    dirty = true; setAddressMode(place.kind, true);
    choose(place.kind, { ...place, label: place.label || field(place.kind).value.trim() || (place.kind === 'origin' ? 'Pinned starting point' : 'Pinned destination') });
  });
  document.addEventListener('jit:pin-unavailable', event => {
    status(event.detail.kind, 'The map could not load. Reload the page, or use stop and walking minutes.', true);
    fallback(event.detail.kind, 'The matched address needs an entrance check. You can use manual walking minutes while the map is unavailable.');
  });
  document.querySelectorAll('[data-search]').forEach(button => {
    button.addEventListener('click', async () => {
      const kind = button.dataset.search, query = field(kind).value.trim();
      if (query.length < 3) { status(kind, 'Enter a street address or specific place first.', true); return; }
      dirty = true; setAddressMode(kind, true);
      invalidate(kind); kinds.forEach(validity);
      const version = versions[kind];
      button.disabled = true;
      status(kind, 'Searching for this address in San Antonio…');
      try {
        const places = await request('search', { query });
        if (version !== versions[kind] || !addressMode(kind)) return;
        const list = field(kind + '-results');
        const requestedNumber = query.match(/^\s*(\d+[a-z]?(?:-\d+[a-z]?)?)\b/i)?.[1]?.toLowerCase();
        const seen = new Set();
        const exact = places.filter(place => !place.approximate && (!requestedNumber || requestedNumber === String(place.houseNumber || '').replace(/\s+/g, '').toLowerCase()));
        for (const place of exact) {
          const key = place.label.toLowerCase() + ':' + Number(place.lat).toFixed(4) + ':' + Number(place.lon).toFixed(4);
          if (seen.has(key)) continue; seen.add(key);
          const choice = document.createElement('button');
          choice.type = 'button'; choice.textContent = place.label;
          if (place.needsConfirmation) {
            const note = document.createElement('small');
            note.textContent = 'Estimated street position · review entrance on map'; choice.append(note);
          }
          choice.addEventListener('click', () => {
            if (version !== versions[kind]) return;
            if (place.needsConfirmation) {
              status(kind, 'Address matched. Check the estimated street position on the map, then confirm your entrance.');
              document.dispatchEvent(new CustomEvent('jit:pin-request', { detail: { kind, area: place, candidate: place } }));
            } else choose(kind, place);
          }); list.append(choice);
        }
        searchAreas[kind] = places.find(place => place.approximate) || places[0];
        if (exact.length) {
          status(kind, exact.some(p => p.needsConfirmation) ? 'Address found. Select the result to check your entrance on the map.' : 'Choose a matching address below, or set the entrance on the map.');
        } else {
          status(kind, requestedNumber && places.length ? `No matching address for house number ${requestedNumber} was returned by the address services.` : 'No exact location found. You can still set your entrance on the map.');
          fallback(kind, 'No need to keep retyping. Set your entrance on the map and confirm the point, or choose your bus stop and enter the walk yourself.');
        }
      } catch (error) {
        if (version === versions[kind]) {
          status(kind, error.message, true);
          fallback(kind, 'Address search is unavailable. You can still set a map point or use a stop and walking minutes.');
        }
      }
      finally { button.disabled = false; }
    });
  });
  document.querySelectorAll('[data-stop-search]').forEach(button => {
    const kind = button.dataset.stopSearch;
    const queryField = field(kind + '-stop-query');
    queryField.addEventListener('input', () => clearStopSearch(kind));
    queryField.addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); button.click(); }
    });
    button.addEventListener('click', async () => {
      clearStopSearch(kind);
      const query = queryField.value.trim(), version = stopVersions[kind];
      const note = field(kind + '-stop-status');
      if (query.length < 2 || !field('date').value) { note.textContent = 'Enter a stop name or ID and choose a travel date.'; return; }
      button.disabled = true; note.textContent = 'Searching VIA stops for this route and date…';
      try {
        const stops = await request('stop-search', { kind, query, date: field('date').value });
        if (version !== stopVersions[kind]) return;
        note.textContent = stops.length ? `${stops.length} matching stops in the supported direction. Check the stop ID and map before selecting.`
          : 'No matching stop on this route and date. Try just one street name, such as Mittman, or check the travel date.';
        for (const stop of stops) {
          const selected = point(kind);
          const distance = selected ? meters(selected, stop) : null;
          const manualRequired = addressMode(kind) && (distance === null || distance > 2000);
          const card = document.createElement('div'); card.className = 'stop-result';
          const title = document.createElement('strong'); title.textContent = stop.name;
          const detail = document.createElement('small');
          detail.textContent = `Stop ${stop.id} · ${kind === 'origin' ? `${form.dataset.firstRoute} toward Martin & Leona` : `${form.dataset.secondRoute} from Martin Opposite Leona`}`;
          if (distance !== null) detail.textContent += ` · ${Math.round(distance)} m straight-line from selected location`;
          const link = document.createElement('a');
          link.href = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(stop.lat + ',' + stop.lon)}`;
          link.textContent = 'View this stop on map ↗'; link.target = '_blank'; link.rel = 'noopener noreferrer';
          const pick = document.createElement('button'); pick.type = 'button'; pick.className = 'lookup-button';
          pick.textContent = manualRequired ? 'Use with manual walking times' : 'Select this stop';
          card.append(title, detail, link);
          if (manualRequired) {
            const warning = document.createElement('p'); warning.className = 'field-note lookup-error';
            warning.textContent = distance === null
              ? 'Find your exact address first for a walking estimate, or switch this commute to manual stops and walking times.'
              : 'Outside 2 km of your selected location. Correct your address, or switch this commute to manual stops and walking times.';
            card.append(warning);
          }
          pick.addEventListener('click', () => {
            if (version !== stopVersions[kind]) return;
            versions[kind]++;
            const select = field(kind + 'Stop');
            if (![...select.options].some(option => option.value === stop.id)) select.add(new Option(`${stop.name} · ${stop.id}`, stop.id));
            select.value = stop.id; walkField(kind).value = '';
            if (manualRequired) {
              manual(kind, stop.id);
            } else {
              select.dispatchEvent(new Event('change', { bubbles: true }));
              select.focus();
            }
          });
          card.append(pick); field(kind + '-stop-results').append(card);
        }
      } catch (error) { if (version === stopVersions[kind]) note.textContent = error.message; }
      finally { button.disabled = false; }
    });
  });
  kinds.forEach(kind => {
    field(kind).addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); document.querySelector('[data-search="' + kind + '"]').click(); } });
    field(kind).addEventListener('input', () => { invalidate(kind); status(kind, addressMode(kind) ? 'Address changed. Find and select the new location.' : 'Using manually selected stops and walking times.'); });
    field(kind + 'Stop').addEventListener('change', async () => {
      if (!addressMode(kind)) { status(kind, 'Stop changed. Check your walking minutes for this stop.'); return; }
      const selected = point(kind), stopId = field(kind + 'Stop').value, version = ++versions[kind];
      walkField(kind).value = '';
      if (!selected || !stopId) return;
      status(kind, 'Calculating the pedestrian walk…');
      try {
        const walk = await request('walk', { kind, ...selected, stopId, date: field('date').value });
        if (version !== versions[kind] || !addressMode(kind)) return;
        walkField(kind).value = walk.minutes;
        status(kind, `${walk.minutes} min walking · ${walk.meters} m. ${walk.source}. You can adjust the minutes below.`);
      } catch (error) {
        if (version === versions[kind]) status(kind, error.message + ' Enter a checked walking time below; no estimate has been filled in.', true);
      }
    });
    walkField(kind).addEventListener('input', () => { versions[kind]++; status(kind, 'Using your entered walking time.'); });
    validity(kind); updateMethod(kind);
    if (point(kind) && resolvedField(kind).value === field(kind).value) showLocation(kind, field(kind).value, point(kind));
  });
  field('locationMode').addEventListener('change', () => kinds.forEach(kind => { validity(kind); updateMethod(kind); }));
  field('date').addEventListener('change', () => {
    kinds.forEach(kind => {
      clearStopSearch(kind); versions[kind]++;
      status(kind, 'Travel date changed. Your stop, map point and walk are kept; service is checked when you plan.');
    });
  });
  // Reveal invalid fields inside collapsed sections before the browser focuses them.
  form.addEventListener('invalid', event => {
    let parent = event.target.parentElement;
    while (parent) { if (parent.tagName === 'DETAILS') parent.open = true; parent = parent.parentElement; }
    field('setup-feedback').textContent = 'Check the highlighted field. For an address that cannot be found, use Set on map or Use stop and walking minutes.';
  }, true);
  form.addEventListener('input', () => { field('setup-feedback').textContent = ''; });
  let transferVersion = 0;
  field('transferWalk').addEventListener('input', () => { transferVersion++; field('transfer-status').textContent = 'Using your entered transfer walking time.'; });
  field('transfer-lookup').addEventListener('click', async event => {
    const button = event.currentTarget, version = ++transferVersion;
    button.disabled = true; field('transfer-status').textContent = 'Calculating the transfer walk…';
    try {
      const walk = await request('walk', { kind: 'transfer', lat: 29.43, lon: -98.50, stopId: '76779', date: field('date').value });
      if (version !== transferVersion) return;
      if (walk.minutes > 30) throw new Error('The mapped transfer exceeds 30 minutes. Check the crossing and enter a verified time.');
      field('transferWalk').value = walk.minutes;
      field('transfer-status').textContent = `${walk.minutes} min · ${walk.meters} m. Allow extra time for traffic signals.`;
    } catch (error) { if (version === transferVersion) field('transfer-status').textContent = error.message + ' Your previous transfer minutes are unchanged.'; }
    finally { button.disabled = false; }
  });
  const toggle = field('auto-refresh');
  if (!toggle) return;
  try { toggle.checked = sessionStorage.getItem('jit-refresh') === 'true'; } catch (_) {}
  toggle.addEventListener('change', () => { try { sessionStorage.setItem('jit-refresh', String(toggle.checked)); } catch (_) {} });
  setInterval(() => {
    const editing = document.activeElement.closest('#live-map-panel') || /^(INPUT|SELECT|TEXTAREA|BUTTON)$/.test(document.activeElement.tagName) && document.activeElement !== toggle;
    if (toggle.checked && !document.hidden && !editing && !pending && !dirty && !field('edit-commute').open && form.checkValidity()) form.requestSubmit(field('plan-button'));
  }, 30000);
})();
