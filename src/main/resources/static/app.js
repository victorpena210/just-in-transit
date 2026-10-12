(() => {
  const form = document.querySelector('#commute-form');
  if (!form) return;
  const field = id => document.getElementById(id);
  const kinds = ['origin', 'destination'];
  const versions = { origin: 0, destination: 0 };
  let pending = 0;
  const walkField = kind => field(kind === 'origin' ? 'walkToStop' : 'walkToWork');
  const resolvedField = kind => field(kind === 'origin' ? 'resolvedOrigin' : 'resolvedDestination');
  const addressMode = () => field('locationMode').value === 'address';
  const point = kind => field(kind + 'Lat').value !== '' && field(kind + 'Lon').value !== ''
    ? { lat: Number(field(kind + 'Lat').value), lon: Number(field(kind + 'Lon').value) } : null;
  function status(kind, message, error = false) {
    const target = field(kind + '-status');
    target.textContent = message;
    target.classList.toggle('lookup-error', error);
  }
  function validity(kind) {
    const ok = !addressMode() || (point(kind) && resolvedField(kind).value === field(kind).value);
    field(kind).setCustomValidity(ok ? '' : 'Find and select this location, or switch to manual stops.');
  }
  function invalidate(kind) {
    versions[kind]++;
    field(kind + 'Lat').value = '';
    field(kind + 'Lon').value = '';
    resolvedField(kind).value = '';
    field(kind + '-results').replaceChildren();
    if (addressMode()) {
      field(kind + 'Stop').replaceChildren(new Option('Find your location again', ''));
      walkField(kind).value = '';
    }
    validity(kind);
  }
  async function request(path, body) {
    const token = form.querySelector('input[name="_csrf"]');
    pending++;
    try {
      const response = await fetch('/maps/' + path, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token.value },
        body: JSON.stringify(body), credentials: 'same-origin', cache: 'no-store'
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.message || result.detail || 'Lookup unavailable. Try again or use manual settings.');
      return result;
    } finally { pending--; }
  }
  async function nearby(kind, selected, version) {
    field(kind + 'Stop').replaceChildren(new Option('Looking for nearby stops…', ''));
    walkField(kind).value = '';
    const stops = await request('stops', { kind, ...selected, date: field('date').value });
    if (version !== versions[kind] || !addressMode()) return;
    const select = field(kind + 'Stop');
    select.replaceChildren(new Option(stops.length ? 'Choose a nearby stop' : 'No supported nearby stops', ''));
    for (const stop of stops) select.add(new Option(`${stop.name} · ${stop.straightLineMeters} m straight-line distance`, stop.id));
    status(kind, stops.length
      ? 'Location selected. Choose a stop below to calculate the walk. Check that the location is your exact entrance.'
      : 'No stop on the supported 24 → 93 journey was found within 2 km for this date. Try a more precise location or another travel date.', !stops.length);
    if (stops.length) {
      const link = document.createElement('a');
      link.href = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(selected.lat + ',' + selected.lon)}`;
      link.textContent = ' Check selected location ↗'; link.target = '_blank'; link.rel = 'noopener noreferrer';
      field(kind + '-status').append(link);
    }
  }
  async function choose(kind, place) {
    const version = ++versions[kind];
    field(kind).value = place.label;
    resolvedField(kind).value = place.label;
    field(kind + 'Lat').value = place.lat;
    field(kind + 'Lon').value = place.lon;
    field(kind + '-results').replaceChildren();
    validity(kind);
    status(kind, 'Finding stops on the supported route…');
    try { await nearby(kind, { lat: place.lat, lon: place.lon }, version); }
    catch (error) { if (version === versions[kind]) status(kind, error.message, true); }
  }
  document.querySelectorAll('[data-search]').forEach(button => {
    button.addEventListener('click', async () => {
      const kind = button.dataset.search, query = field(kind).value.trim();
      if (query.length < 3) { status(kind, 'Enter a street address or specific place first.', true); return; }
      field('locationMode').value = 'address';
      invalidate(kind); kinds.forEach(validity);
      const version = versions[kind];
      button.disabled = true;
      status(kind, 'Finding locations…');
      try {
        const places = await request('search', { query });
        if (version !== versions[kind] || !addressMode()) return;
        status(kind, places.length ? 'Select the matching address or entrance.' : 'No matching location found. Try the street address with San Antonio, TX.', !places.length);
        const list = field(kind + '-results');
        for (const place of places) {
          const choice = document.createElement('button');
          choice.type = 'button'; choice.textContent = place.label;
          choice.addEventListener('click', () => choose(kind, place)); list.append(choice);
        }
      } catch (error) { if (version === versions[kind]) status(kind, error.message, true); }
      finally { button.disabled = false; }
    });
  });
  kinds.forEach(kind => {
    field(kind).addEventListener('input', () => { invalidate(kind); status(kind, addressMode() ? 'Address changed. Find and select the new location.' : 'Using manually selected stops and walking times.'); });
    field(kind + 'Stop').addEventListener('change', async () => {
      if (!addressMode()) { status(kind, 'Stop changed. Check your walking minutes for this stop.'); return; }
      const selected = point(kind), stopId = field(kind + 'Stop').value, version = ++versions[kind];
      walkField(kind).value = '';
      if (!selected || !stopId) return;
      status(kind, 'Calculating the pedestrian walk…');
      try {
        const walk = await request('walk', { kind, ...selected, stopId, date: field('date').value });
        if (version !== versions[kind] || !addressMode()) return;
        walkField(kind).value = walk.minutes;
        status(kind, `${walk.minutes} min walking · ${walk.meters} m. ${walk.source}. You can adjust the minutes below.`);
      } catch (error) {
        if (version === versions[kind]) status(kind, error.message + ' Enter a checked walking time below; no estimate has been filled in.', true);
      }
    });
    walkField(kind).addEventListener('input', () => { versions[kind]++; status(kind, 'Using your entered walking time.'); });
    validity(kind);
  });
  field('locationMode').addEventListener('change', () => {
    kinds.forEach(kind => {
      versions[kind]++; field(kind + '-results').replaceChildren(); validity(kind);
      status(kind, addressMode() ? 'Find and select an address, then choose a nearby stop.' : 'Manual mode: verify the selected stop and walking time. Reload to restore the full stop lists.');
    });
    // Re-render the full date-specific stop lists without saving or running a lookup.
    if (!addressMode()) {
      form.action = '/settings'; form.submit();
    }
  });
  field('date').addEventListener('change', async () => {
    if (!addressMode()) return;
    for (const kind of kinds) {
      const selected = point(kind), version = ++versions[kind];
      if (!selected || !field('date').value) continue;
      try { await nearby(kind, selected, version); }
      catch (error) { if (version === versions[kind]) status(kind, error.message, true); }
    }
  });
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
    const editing = /^(INPUT|SELECT|TEXTAREA|BUTTON)$/.test(document.activeElement.tagName) && document.activeElement !== toggle;
    if (toggle.checked && !document.hidden && !editing && !pending && form.checkValidity()) form.requestSubmit(field('plan-button'));
  }, 30000);
})();
