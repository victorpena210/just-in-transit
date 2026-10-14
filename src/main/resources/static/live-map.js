(() => {
  const panel = document.getElementById('live-map-panel');
  if (!panel) return;
  const $ = id => document.getElementById(id);
  const form = $('commute-form');
  let token = panel.dataset.token || '', option = 0, active = panel.dataset.active === 'true';
  let data = null, received = 0, failed = false, inFlight = false, generation = 0;
  let map, stopsLayer, busesLayer, pinsLayer, userLayer, fitOnce = false, pinKind = null, pinCandidate = null;
  let locationWanted = false, watchId = null, userPoint = null;
  const pins = {};
  const local = p => Number.isFinite(p.lat) && Number.isFinite(p.lon) && p.lat >= 29.1 && p.lat <= 29.9 && p.lon >= -99.1 && p.lon <= -98;
  const textNode = (tag, text, cls) => { const node = document.createElement(tag); node.textContent = text; if (cls) node.className = cls; return node; };
  const serverNow = () => data ? Date.parse(data.now) + performance.now() - received : Date.now();
  const stamp = iso => iso ? new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' }) : 'time unknown';
  const duration = seconds => {
    const s = Math.max(0, Math.ceil(seconds)), h = Math.floor(s / 3600);
    return (h ? h + ':' : '') + String(Math.floor(s % 3600 / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
  };
  function resetPlan() {
    if (active) return;
    token = ''; generation++;
    if (data) data = { ...data, countdowns: [], buses: data.buses.map(bus => ({ ...bus, selected: false })), walkMinutes: null };
    $('map-countdowns').replaceChildren(); $('map-walk-note').textContent = '';
    $('map-plan-status').textContent = 'Settings changed. Find your departure again to update the countdowns.';
    document.querySelectorAll('[data-map-option]').forEach(button => button.disabled = true);
  }
  function updatePins() {
    if (!map) return;
    pinsLayer.clearLayers();
    for (const kind of ['origin', 'destination']) if (pins[kind]) {
      L.circleMarker([pins[kind].lat, pins[kind].lon], { radius: 8, color: '#b45d28', fillColor: '#fff6df', fillOpacity: 1 })
        .bindPopup(textNode('span', kind === 'origin' ? 'Your starting point' : 'Your destination')).addTo(pinsLayer);
    }
  }
  function fitStops() {
    if (!map) return;
    const points = (data?.stops || []).map(s => [s.lat, s.lon]);
    for (const p of Object.values(pins)) points.push([p.lat, p.lon]);
    if (points.length) map.fitBounds(points, { padding: [35, 35], maxZoom: 16 });
  }
  function drawStops() {
    if (!map) return;
    stopsLayer.clearLayers();
    for (const stop of data?.stops || []) {
      const label = textNode('div', stop.name); label.append(textNode('small', 'Stop ' + stop.id));
      L.circleMarker([stop.lat, stop.lon], { radius: 6, color: '#17382f', fillColor: '#edf7e8', fillOpacity: 1 }).bindPopup(label).addTo(stopsLayer);
    }
    if (!fitOnce && !pinKind && !locationWanted && data?.stops.length) { fitOnce = true; fitStops(); }
  }
  let lastBusKey = '';
  function drawBuses() {
    const now = serverNow(), buses = (data?.buses || []).filter(b => now <= Date.parse(b.validUntil) + 510000);
    const key = JSON.stringify(buses) + buses.map(b => !failed && b.fresh && now <= Date.parse(b.validUntil)).join(',') + failed;
    if (key === lastBusKey) return;
    lastBusKey = key; busesLayer?.clearLayers(); $('map-bus-list').replaceChildren();
    let liveCount = 0, selectedCount = 0;
    for (const bus of buses) {
      const live = !failed && bus.fresh && now <= Date.parse(bus.validUntil);
      if (live) liveCount++; if (bus.selected) selectedCount++;
      const description = `${bus.route} · ${bus.headsign} · vehicle ${bus.id}${bus.selected ? ' · selected trip' : ''} · ${live ? 'reported ' : 'last report, unconfirmed · '}${stamp(bus.reportedAt)}`;
      $('map-bus-list').append(textNode('li', description));
      if (map) {
        const badge = textNode('span', (bus.selected ? '★ ' : '') + bus.route);
        const icon = L.divIcon({ html: badge, className: `map-bus ${live ? '' : 'stale'} ${bus.selected ? 'selected' : ''}`, iconSize: [48, 32], iconAnchor: [24, 16] });
        L.marker([bus.lat, bus.lon], { icon, title: description }).bindPopup(textNode('div', description)).addTo(busesLayer);
      }
    }
    if (!buses.length) $('map-bus-list').append(textNode('li', 'No matching bus positions available. Missing positions do not mean no buses are running.'));
    $('map-feed-status').textContent = failed ? 'Could not refresh. Markers and countdowns are unconfirmed. Try Refresh buses.' :
      `${liveCount} fresh bus reports · ${buses.length - liveCount} unconfirmed. ${data?.vehicleStatus || ''} ${token || active ? (selectedCount ? '★ matches the trip and service date in your plan.' : 'No position matched your selected trip and service date; countdowns can still use arrival data.') : 'Shows buses serving the selected stops in the supported direction.'}`;
  }
  function renderClocks() {
    const container = $('map-countdowns'); container.replaceChildren();
    for (const c of data?.countdowns || []) {
      const card = textNode('div', '', 'countdown-card'); card.dataset.clockId = c.id;
      card.append(textNode('span', c.label, 'countdown-label'), textNode('strong', '', 'countdown-value'), textNode('small', c.source));
      container.append(card);
    }
    tick();
  }
  function tick() {
    const now = serverNow();
    for (const [i, c] of (data?.countdowns || []).entries()) {
      const card = $('map-countdowns').children[i]; if (!card) continue;
      const stale = failed || now > Date.parse(c.validUntil), seconds = (Date.parse(c.target) - now) / 1000;
      card.querySelector('strong').textContent = !c.target ? 'Unavailable' : stale ? 'Unconfirmed' : seconds <= 0 ? 'Check now' : duration(seconds);
      card.querySelector('small').textContent = stale ? 'Refresh needed · last basis: ' + c.source : c.target && seconds <= 0 ? c.passed : c.source;
      card.classList.toggle('countdown-warning', stale || !c.target || seconds <= 0);
    }
    drawBuses();
    const usable = userPoint && Date.now() - userPoint.time <= 30000 && userPoint.accuracy <= 100 && local(userPoint);
    if ($('map-use-location')) $('map-use-location').disabled = !usable;
    if (userPoint && Date.now() - userPoint.time > 30000 && userLayer) {
      userLayer.eachLayer(layer => layer.setStyle?.({ color: '#68736c', fillColor: '#68736c' }));
      $('map-location-status').textContent = 'Your last location is more than 30 seconds old. Tap Find me to update, or pin your entrance.';
    }
  }
  async function refreshJourney() {
    if (!active || document.activeElement?.closest('#active-journey form')) return;
    const current = $('journey-refresh');
    if (current && !current.checked) return;
    const response = await fetch('/', { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(30000) });
    if (!response.ok || response.redirected) return;
    const html = new DOMParser().parseFromString(await response.text(), 'text/html');
    const next = html.getElementById('active-journey');
    if (next && $('active-journey')) $('active-journey').replaceWith(next);
    else if (!next) window.location.replace('/');
  }
  async function refresh() {
    if (inFlight || document.hidden) return;
    if (!navigator.onLine) { failed = true; tick(); return; }
    const version = generation;
    const origin = $('originStop')?.value || panel.dataset.originStop || '81799';
    const destination = $('destinationStop')?.value || panel.dataset.destinationStop || '83463';
    const params = new URLSearchParams({ token, option: String(option), originStop: origin, destinationStop: destination, corridor: panel.dataset.corridor || 'work' });
    inFlight = true; $('map-refresh').disabled = true;
    try {
      const response = await fetch('/maps/live?' + params, { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(30000) });
      if (!response.ok || response.redirected) throw new Error('Map update unavailable');
      const result = await response.json();
      if (version !== generation) return;
      if (!result.now || !Array.isArray(result.countdowns) || !Array.isArray(result.buses)) throw new Error('Invalid map update');
      data = result; received = performance.now(); failed = false;
      if (active && !result.active) { window.location.replace('/'); return; }
      if (!active && result.active) { window.location.replace('/'); return; }
      $('map-plan-status').textContent = result.message;
      $('map-walk-note').textContent = result.walkMinutes == null ? '' : `${!result.active || result.phase === 'WALKING_TO_24' ? result.walkMinutes + ' min entered walk to the ' + result.firstRoute + '. ' : ''}Countdown uses your selected walking times and buffers; the blue dot does not recalculate your plan.`;
      drawStops(); renderClocks(); await refreshJourney();
    } catch (_) { if (version === generation) { failed = true; tick(); } }
    finally { inFlight = false; $('map-refresh').disabled = false; if (version !== generation) refresh(); }
  }
  function stopWatch(clear = false) {
    if (watchId !== null) navigator.geolocation.clearWatch(watchId);
    watchId = null;
    if (clear) { locationWanted = false; userPoint = null; userLayer?.clearLayers(); $('map-stop-location').hidden = true; tick(); }
  }
  function openMap() { if ($('map-details')) $('map-details').open = true; map?.invalidateSize(); }
  $('map-details')?.addEventListener('toggle', () => { if ($('map-details').open) { map?.invalidateSize(); if (!pinKind) fitStops(); } });
  function locate() {
    openMap();
    panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
    if (!window.isSecureContext) { $('map-location-status').textContent = 'Location access needs HTTPS on your phone. Use a map pin for now.'; return; }
    if (!navigator.geolocation) { $('map-location-status').textContent = 'Location access is unavailable in this browser. Use a map pin.'; return; }
    stopWatch(); locationWanted = true; $('map-stop-location').hidden = false;
    $('map-location-status').textContent = 'Finding your location…'; let centered = false;
    watchId = navigator.geolocation.watchPosition(position => {
      const p = { lat: position.coords.latitude, lon: position.coords.longitude, accuracy: position.coords.accuracy, time: position.timestamp };
      if (!Number.isFinite(p.lat) || !Number.isFinite(p.lon) || !Number.isFinite(p.accuracy) || p.accuracy < 0 || !Number.isFinite(p.time)) return;
      userPoint = p;
      if (map) {
        userLayer.clearLayers(); L.circle([p.lat, p.lon], { radius: p.accuracy, color: '#2269c3', weight: 1, fillOpacity: .12 }).addTo(userLayer);
        L.circleMarker([p.lat, p.lon], { radius: 7, color: '#fff', weight: 3, fillColor: '#2269c3', fillOpacity: 1 }).bindPopup(textNode('span', 'You · accuracy about ' + Math.round(p.accuracy) + ' m')).addTo(userLayer);
        if (!centered && local(p)) { map.setView([p.lat, p.lon], 17); centered = true; }
      }
      $('map-location-status').textContent = !local(p) ? 'Your reported location is outside the supported San Antonio area. Pin your starting point instead.' : p.accuracy > 100 ? `Location is approximate (about ${Math.round(p.accuracy)} m). Pin your entrance for a reliable starting point.` : `Location accuracy: about ${Math.round(p.accuracy)} m. Check the dot before using it as your start.`;
      tick();
    }, error => {
      stopWatch(true);
      $('map-location-status').textContent = error.code === 1 ? 'Location permission was denied. You can still pin your entrance on the map.' : 'Could not get a current location. Try again or pin your entrance.';
    }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 10000 });
  }
  function usePoint(kind, p, label) {
    if (!local(p)) { $('map-location-status').textContent = 'Choose a point in the San Antonio area.'; return; }
    resetPlan(); pins[kind] = p; updatePins();
    document.dispatchEvent(new CustomEvent('jit:location-selected', { detail: { kind, lat: p.lat, lon: p.lon, label } }));
    $('edit-commute').open = true;
    $(kind + 'Stop').scrollIntoView({ behavior: 'smooth', block: 'center' });
    $('map-location-status').textContent = `Updated ${kind === 'origin' ? 'starting point' : 'destination'}. Choose a nearby stop below to calculate the walk.`;
  }
  function cancelPin() { pinKind = null; pinCandidate = null; panel.classList.remove('choosing-pin'); $('map-crosshair').hidden = true; $('map-pin-confirm').hidden = true; }
  for (const kind of ['origin', 'destination']) {
    const lat = panel.dataset[kind + 'Lat'], lon = panel.dataset[kind + 'Lon'];
    if (lat && lon && local({ lat: Number(lat), lon: Number(lon) })) pins[kind] = { lat: Number(lat), lon: Number(lon) };
  }
  if (window.L) {
    map = L.map('commute-map', { scrollWheelZoom: false }).setView([29.44, -98.48], 12);
    stopsLayer = L.layerGroup().addTo(map); busesLayer = L.layerGroup().addTo(map); pinsLayer = L.layerGroup().addTo(map); userLayer = L.layerGroup().addTo(map);
    const tileUrl = panel.dataset.tileUrl || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
    if (tileUrl.startsWith('https://') || tileUrl.startsWith('/')) {
      const tiles = L.tileLayer(tileUrl, { maxZoom: 19, attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors' }).addTo(map);
      tiles.on('tileerror', () => { $('map-tile-status').textContent = 'Map background could not load. Bus reports and countdowns remain available; try refreshing the page.'; });
      if (panel.dataset.tileCredit) { const credit = textNode('span', panel.dataset.tileCredit); map.attributionControl.addAttribution(credit.outerHTML); }
    } else $('map-tile-status').textContent = 'Map tile configuration must use HTTPS.';
    updatePins();
  } else {
    $('map-tile-status').textContent = 'The map library could not load. Countdown and bus-list updates are still available.';
    document.querySelectorAll('[data-map-pin],#map-fit').forEach(button => button.disabled = true);
  }
  $('map-refresh').addEventListener('click', refresh);
  $('map-fit').addEventListener('click', fitStops);
  $('map-find-me').addEventListener('click', locate);
  $('map-stop-location').addEventListener('click', () => { stopWatch(true); $('map-location-status').textContent = 'Location access stopped for this page.'; });
  $('map-use-location')?.addEventListener('click', () => { if (userPoint && Date.now() - userPoint.time <= 30000 && userPoint.accuracy <= 100) usePoint('origin', userPoint); });
  function beginPin(kind, area, candidate) {
    if (!map) { document.dispatchEvent(new CustomEvent('jit:pin-unavailable', { detail: { kind } })); return; }
    if (active || !['origin', 'destination'].includes(kind)) return;
    openMap();
    pinKind = kind; pinCandidate = candidate || null; panel.classList.add('choosing-pin');
    $('map-crosshair').hidden = false; $('map-pin-confirm').hidden = false;
    const generalArea = area && local(area) ? area : null;
    $('map-pin-instructions').textContent = `${candidate ? candidate.label + ' — estimated street position from U.S. Census address ranges. Check the building entrance. ' : generalArea ? 'This is a general search area, not your confirmed address. ' : ''}Move the map until your ${kind === 'origin' ? 'starting entrance' : 'destination entrance'} is under the +. Zoom in, then choose Use this point.`;
    const p = (candidate ? generalArea : pins[kind] || generalArea) || data?.stops.find(s => s.id === (kind === 'origin' ? ($('originStop')?.value || panel.dataset.originStop) : ($('destinationStop')?.value || panel.dataset.destinationStop)));
    if (p) map.setView([p.lat, p.lon], candidate ? 18 : generalArea && !pins[kind] ? 15 : 17); else map.setZoom(16);
    map.invalidateSize(); panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
    $('commute-map').focus({ preventScroll: true });
  }
  document.querySelectorAll('[data-map-pin]').forEach(button => button.addEventListener('click', () => beginPin(button.dataset.mapPin)));
  document.addEventListener('jit:pin-request', event => beginPin(event.detail.kind, event.detail.area, event.detail.candidate));
  document.querySelector('[data-locate-start]')?.addEventListener('click', locate);
  document.addEventListener('jit:location-cleared', event => { resetPlan(); delete pins[event.detail.kind]; if (pinKind === event.detail.kind) cancelPin(); updatePins(); });
  $('map-save-pin')?.addEventListener('click', () => { if (!pinKind || !map) return; const p = map.getCenter(), kind = pinKind; if (!local({ lat: p.lat, lon: p.lng })) { $('map-pin-instructions').textContent = 'Choose a point in the San Antonio area.'; return; } const label = pinCandidate?.label; cancelPin(); usePoint(kind, { lat: p.lat, lon: p.lng }, label); });
  $('map-cancel-pin')?.addEventListener('click', () => { const kind = pinKind; cancelPin(); if (kind) { $('edit-commute').open = true; $(kind).focus(); } });
  document.addEventListener('jit:point-resolved', event => { resetPlan(); pins[event.detail.kind] = event.detail; updatePins(); });
  if (form) {
    form.addEventListener('input', event => {
      if (event.target.closest('.stop-finder')) return;
      resetPlan();
      if (['origin', 'destination'].includes(event.target.id)) { delete pins[event.target.id]; updatePins(); }
    });
    form.addEventListener('change', event => {
      if (event.target.id !== 'auto-refresh') resetPlan();
      if (['originStop', 'destinationStop'].includes(event.target.id)) refresh();
    });
  }
  document.addEventListener('click', event => {
    const button = event.target.closest('[data-map-option]');
    if (!button || button.disabled || !token) return;
    openMap();
    option = Number(button.dataset.mapOption); generation++; data = null; $('map-countdowns').replaceChildren();
    panel.scrollIntoView({ behavior: 'smooth', block: 'start' }); refresh();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stopWatch();
    else { if (locationWanted) locate(); if ($('map-auto-refresh').checked) refresh(); tick(); }
  });
  window.addEventListener('pagehide', () => stopWatch());
  window.addEventListener('offline', () => { failed = true; tick(); });
  window.addEventListener('online', () => { if ($('map-auto-refresh').checked) refresh(); });
  $('map-auto-refresh').addEventListener('change', () => { if ($('map-auto-refresh').checked) refresh(); });
  setInterval(() => { if ($('map-auto-refresh').checked) refresh(); }, 30000);
  setInterval(tick, 1000);
  refresh();
})();
