'use strict';
// Sony home screen mockup. 960 x 540 CSS px = dp; values from tokens.json. Network data loads after the first paint
// (weather when idle, the radar thumbnail after the weather, radar frames only when the radar opens).

// Home: Etobicoke until the server answers with the place from config.properties (postal_code, or latitude/longitude).
const LOC = { name: 'Etobicoke', lat: 43.6205, lon: -79.5132, tz: 'America/Toronto', country: 'CA' };
const RADAR = { rain: 'RADAR_1KM_RRAI', snow: 'RADAR_1KM_RSNO' };
const GEOMET = 'https://geo.weather.gc.ca/geomet';
const NOAA = 'https://nowcoast.noaa.gov/geoserver/weather_radar/';
const SPRINGS = { tile: [0.34, 0.74], button: [0.26, 0.85], card: [0.32, 0.78], sheet: [0.38, 0.9] };
const TOAST_MS = 6000, MAX_APPS = 5;

// Sample of what a Sony might have; Phase 1 swaps in the TV's real list.
const APPS = [
  { key: 'youtube', name: 'YouTube', a: '#1C1C1E', b: '#09090A', logo: '<span class="play"></span>YouTube' },
  { key: 'netflix', name: 'Netflix', a: '#0A0A0A', b: '#000000', logo: 'NETFLIX' },
  { key: 'prime', name: 'Prime Video', a: '#2AA8FF', b: '#0A68D4', logo: 'prime video' },
  { key: 'disney', name: 'Disney+', a: '#173584', b: '#08143F', logo: 'Disney+' },
  { key: 'crave', name: 'Crave', a: '#1E3A8A', b: '#0B1638', logo: 'crave' },
  { key: 'appletv', name: 'Apple TV', a: '#1C1C1E', b: '#000000', logo: 'Apple TV' },
  { key: 'spotify', name: 'Spotify', a: '#191414', b: '#000000', logo: 'Spotify' },
  { key: 'cbcgem', name: 'CBC Gem', a: '#E2231A', b: '#9E0F0A', logo: 'CBC Gem' },
  { key: 'plex', name: 'Plex', a: '#282A2D', b: '#111111', logo: 'plex' },
  { key: 'twitch', name: 'Twitch', a: '#9146FF', b: '#5B1FD1', logo: 'twitch' },
];

const $ = (s) => document.querySelector(s);
const tv = $('#tv');
const idle = window.requestIdleCallback || ((f) => setTimeout(f, 200));
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* private window: keep it in memory */ } },
};
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// ---------- Springs: Spring.java's integration, turned into CSS linear() curves ----------

function springCurve(response, damping) {
  const omega = 2 * Math.PI / response, stiffness = omega * omega, friction = 2 * damping * omega;
  let value = 0, velocity = 0;
  const points = [0];
  for (let frame = 0; frame < 240; frame++) {
    for (let i = 0; i < 4; i++) {   // 4 ms steps, like Spring.step
      const accel = -stiffness * (value - 1) - friction * velocity;
      velocity += accel * 0.004;
      value += velocity * 0.004;
    }
    points.push(+value.toFixed(4));
    if (Math.abs(value - 1) < 0.0008 && Math.abs(velocity) < 0.004) break;
  }
  points[points.length - 1] = 1;
  return { ms: Math.round(points.length * 16), curve: 'linear(' + points.join(', ') + ')' };
}

for (const [name, [r, d]] of Object.entries(SPRINGS)) {
  const s = springCurve(r, d);
  tv.style.setProperty('--' + name + '-e', s.curve);
  tv.style.setProperty('--' + name + '-d', s.ms + 'ms');
}

// ---------- Scale: 2x (1 CSS px = 1 dp on a 1080p screen), or fit the window ----------

let forceFit = false;
function fit() {
  const room = Math.min(innerWidth / 960, (innerHeight - 26) / 540);
  const s = !forceFit && room >= 2 ? 2 : room;
  tv.style.transform = 'translate(-50%, -50%) scale(' + s + ')';
  $('#scale').textContent = s.toFixed(2) + '×' + (s === 2 ? '' : ' (fit; widen the window for 2×)');
}
addEventListener('resize', fit);
fit();

// ---------- Time, at home ----------

function formats(tz) {
  return {
    time: new Intl.DateTimeFormat('en-US', { timeZone: tz, hour: 'numeric', minute: '2-digit' }),
    date: new Intl.DateTimeFormat('en-US', { timeZone: tz, weekday: 'long', month: 'short', day: 'numeric' }),
    local: new Intl.DateTimeFormat('sv-SE', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }),
  };
}
let fmt = formats(LOC.tz);

async function loadHome() {
  try {
    const h = await (await fetch('/api/home')).json();
    Object.assign(LOC, { name: h.city || 'Home', lat: h.lat, lon: h.lon, tz: h.tz || LOC.tz, country: h.country || LOC.country });
    fmt = formats(LOC.tz);
    if (h.error) toast(h.error + ' Using latitude and longitude from config.properties.');
  } catch (e) { /* server down: Etobicoke */ }
  $('#wx-city').textContent = LOC.name;
  $('#radar-place').textContent = 'Radar · ' + LOC.name;
  tick();
}
/** "2026-10-20T14:31": the same form as Open-Meteo's local times, so they compare as strings. */
const localNow = () => fmt.local.format(new Date()).replace(' ', 'T');
/** "18:31" from Open-Meteo's "2026-10-20T18:31" as "6:31 PM". */
function clockOf(iso) {
  const [h, m] = iso.slice(11, 16).split(':').map(Number);
  return ((h + 11) % 12 + 1) + ':' + String(m).padStart(2, '0') + (h < 12 ? ' AM' : ' PM');
}

let nightOverride = null;
function isNight() {
  if (nightOverride !== null) return nightOverride;
  const now = localNow();
  if (weather) return now < weather.sunrise || now >= weather.sunset;
  const h = +now.slice(11, 13);
  return h < 7 || h >= 19;
}

function tick() {
  const h = +localNow().slice(11, 13), now = new Date();
  $('#box-date').textContent = fmt.date.format(now);
  $('#greeting').textContent = h < 5 || h >= 17 ? 'Good evening' : h < 12 ? 'Good morning' : 'Good afternoon';
  const night = isNight();
  if (night !== tv.classList.contains('night') && thumbDone) { thumbDone = false; idle(loadThumb); }   // the dark map
  tv.classList.toggle('night', night);
  showClock();
}

/** The clock, or the focused round button's name in its place (the tvOS tip, without covering Ask AI). */
function showClock() {
  const tip = focused && focused.classList.contains('round') ? focused.dataset.tip : '';
  const now = new Date();
  $('#clock').textContent = tip || fmt.time.format(now);
  $('#box-clock').textContent = fmt.time.format(now).replace(/ [AP]M$/, '');   // the two clocks change together
  $('#clock').classList.toggle('tip', !!tip);
}

// ---------- Weather (Google Weather with the maps key, else Open-Meteo; Celsius) ----------

let weather = null;

function symbolFor(code, day) {   // Symbols.weather
  if (code <= 1) return day ? 'sunny' : 'clear_night';
  if (code === 2) return day ? 'partly_cloudy_day' : 'partly_cloudy_night';
  if (code === 3) return 'cloud';
  if (code === 45 || code === 48) return 'foggy';
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'snowflake';
  if (code >= 95) return 'thunderstorm';
  return 'rainy';
}
function conditionFor(code, day) {   // HomeData.condition, English
  if (code === 0) return 'Clear';
  if (code === 1) return day ? 'Mostly Sunny' : 'Mostly Clear';
  if (code === 2) return 'Partly Cloudy';
  if (code === 3) return 'Cloudy';
  if (code === 45 || code === 48) return 'Fog';
  if (code >= 51 && code <= 57) return 'Drizzle';
  if (code === 65 || code === 67 || code === 82) return 'Heavy Rain';
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'Snow';
  if (code >= 95) return 'Thunderstorms';
  return 'Rain';
}
const sym = (name) => '<i class="sym" style="--s:url(/app/assets/symbols/' + name + '.png)"></i>';
const deg = (t) => Math.round(t) + '°';

/** Google Weather through the server (it holds the maps key); null when there's no key or Google failed. */
async function googleWeather() {
  try {
    const r = await fetch('/api/weather');
    const d = await r.json();
    if (r.ok && !d.error) return d;
    if (d.error !== 'no_maps_key') console.warn('[mockup] ' + d.error + ' (showing Open-Meteo)');
  } catch (e) { /* server down: Open-Meteo */ }
  return null;
}

/** Open-Meteo, keyless: the fallback, in the same shape. */
async function openMeteo() {
  const url = 'https://api.open-meteo.com/v1/forecast?latitude=' + LOC.lat + '&longitude=' + LOC.lon +
    '&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day,precipitation_probability' +
    '&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&temperature_unit=celsius&timezone=' + encodeURIComponent(LOC.tz) + '&forecast_days=2';
  const r = await fetch(url);
  if (!r.ok) throw new Error('HTTP ' + r.status);
  const d = await r.json();
  const c = d.current, start = d.hourly.time.findIndex((t) => t > c.time);
  return {
    source: 'Open-Meteo', temp: c.temperature_2m, day: !!c.is_day,
    symbol: symbolFor(c.weather_code, !!c.is_day), condition: conditionFor(c.weather_code, !!c.is_day),
    max: d.daily.temperature_2m_max[0], min: d.daily.temperature_2m_min[0],
    sunrise: d.daily.sunrise[0], sunset: d.daily.sunset[0], sunriseNext: d.daily.sunrise[1],
    hours: d.hourly.time.slice(start, start + 6).map((t, i) => ({
      t, temp: d.hourly.temperature_2m[start + i], symbol: symbolFor(d.hourly.weather_code[start + i], !!d.hourly.is_day[start + i]),
      pop: d.hourly.precipitation_probability[start + i],
    })),
    at: Date.now(),
  };
}

async function loadWeather() {
  const t0 = performance.now();
  try {
    weather = (await googleWeather()) || (await openMeteo());
    renderWeather();
    console.info('[mockup] weather (' + weather.source + ') in ' + Math.round(performance.now() - t0) + ' ms');
  } catch (e) {
    $('#wx-cond').textContent = 'No weather data right now.';
    $('#wx-meta').textContent = 'Retrying in 1 min';
    setTimeout(loadWeather, 60000);
    return;
  }
  setTimeout(loadWeather, 60 * 60000);   // hourly
}

function renderWeather() {
  const w = weather;
  $('#wx-icon').style.setProperty('--s', 'url(/app/assets/symbols/' + w.symbol + '.png)');
  $('#wx-temp').textContent = deg(w.temp);
  $('#wx-cond').textContent = w.condition;
  $('#wx-hl').textContent = 'H:' + deg(w.max) + '   L:' + deg(w.min);
  $('#wx-hours').innerHTML = w.hours.map((h) =>
    '<div><div class="h num">' + hourOf(h.t) + '</div>' +
    sym(h.symbol) + '<div class="t num">' + deg(h.temp) + '</div></div>').join('');
  const rain = Math.max(...w.hours.map((h) => h.pop || 0));
  $('#wx-sun').innerHTML = '<span>' + sym('sunrise') + clockOf(w.sunrise) + '</span><span>' + sym('sunset') + clockOf(w.sunset) + '</span>' +
    '<span>' + sym('rainy') + rain + '% rain</span>';
  $('#wx-meta').textContent = w.source + ' · updated ' + fmt.time.format(new Date(w.at)) + ' · OK for radar';
  tick();
}
/** "2026-10-20T15:00" (Etobicoke wall clock) as "3 PM". */
function hourOf(iso) {
  const h = +iso.slice(11, 13);
  return ((h + 11) % 12 + 1) + ' ' + (h < 12 ? 'AM' : 'PM');
}

// ---------- Maps (Google with the maps key, else Esri's keyless canvas; Web Mercator) and radar (GeoMet or NOAA) ----------

const ESRI = 'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/';
const MAP_NAMES = { google: 'Google Maps', mapbox: 'Mapbox', maptiler: 'MapTiler' };

function project(lat, lon, z) {
  const n = 256 * 2 ** z, s = Math.sin(lat * Math.PI / 180);
  return { x: (lon + 180) / 360 * n, y: (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n };
}
function unproject(x, y, z) {
  const n = 256 * 2 ** z;
  return { lat: Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))) * 180 / Math.PI, lon: x / n * 360 - 180 };
}

/** Lays the map tiles for a w x h box centered on `center`; returns the EPSG:3857 box for WMS and the tile loads. */
function buildMap(el, w, h, z, center, night) {
  el.textContent = '';
  const c = project(center.lat, center.lon, z), x0 = c.x - w / 2, y0 = c.y - h / 2;
  const tone = night ? 'World_Dark_Gray_' : 'World_Light_Gray_', loads = [];
  // With a maps key: the provider's full map, through the server (it holds the key). Without: Esri's grey canvas, with
  // its place names above the radar (z-index in CSS).
  const keyed = settings.maps && settings.maps.hasKey;
  const layers = keyed ? [['Keyed', 'base']] : [['Base', 'base'], ['Reference', 'names']];
  setCredit(keyed ? settings.maps.credit : 'Esri, HERE, Garmin, © OpenStreetMap contributors');
  for (const [layer, cls] of layers) {
    for (let tx = Math.floor(x0 / 256); tx <= Math.floor((x0 + w - 1) / 256); tx++) {
      for (let ty = Math.floor(y0 / 256); ty <= Math.floor((y0 + h - 1) / 256); ty++) {
        const img = new Image(256, 256);
        img.className = cls;
        img.decoding = 'async';
        img.style.left = (tx * 256 - x0) + 'px';
        img.style.top = (ty * 256 - y0) + 'px';
        img.alt = '';
        if (keyed) img.addEventListener('error', () => mapKeyFailed(img, tone, z, tx, ty), { once: true });
        if (cls === 'base') loads.push(loaded(img));
        img.src = keyed ? '/api/tile/' + z + '/' + tx + '/' + ty + '?tone=' + (night ? 'night' : 'day')
          : ESRI + tone + layer + '/MapServer/tile/' + z + '/' + ty + '/' + tx;
        el.appendChild(img);
      }
    }
  }
  const n = 256 * 2 ** z, W = 2 * Math.PI * 6378137;
  const mx = (x) => x / n * W - W / 2, my = (y) => W / 2 - y / n * W;
  if (keyed && settings.maps.provider === 'google') googleCredit(el, z, x0, y0, w, h, night);
  // The blue dot: home, wherever it falls on this map.
  const you = el.parentNode.querySelector('.you'), home = project(LOC.lat, LOC.lon, z);
  if (you) { you.style.left = (home.x - x0) + 'px'; you.style.top = (home.y - y0) + 'px'; }
  return { bbox: [mx(x0), my(y0 + h), mx(x0 + w), my(y0)].map((v) => v.toFixed(1)).join(','), w, h, loads, x0, y0, z };
}
/** A keyed tile failed (wrong key, quota): that tile comes from the free map instead, and the toast says why once. */
let mapWarned = false;
function mapKeyFailed(img, tone, z, tx, ty) {
  img.src = ESRI + tone + 'Base/MapServer/tile/' + z + '/' + ty + '/' + tx;
  const logo = img.parentNode && img.parentNode.querySelector('.glogo');
  if (logo) logo.remove();   // not Google's map any more
  delete $('#thumb-meta').dataset.credit;
  setCredit('Esri, HERE, Garmin, © OpenStreetMap contributors');
  if (mapWarned) return;
  mapWarned = true;
  toast('The ' + MAP_NAMES[settings.maps.provider] + ' key did not work, so this is the free map. Check maps_key in config.properties (the server terminal says why).');
}

/**
 * Google's terms for Map Tiles: their logo (16 to 19 dp, unmodified) on the map and the copyright line from the
 * viewport endpoint for what's on screen, neither one covered.
 */
async function googleCredit(el, z, x0, y0, w, h, night) {
  const logo = document.createElement('img');
  logo.className = 'glogo';
  logo.alt = 'Google';
  logo.src = 'https://maps.gstatic.com/mapfiles/api-3/images/google4_hdpi.png';   // Google's default map is light, day and night
  el.appendChild(logo);
  const n = 256 * 2 ** z, lon = (x) => x / n * 360 - 180;
  const lat = (y) => Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))) * 180 / Math.PI;
  const q = new URLSearchParams({ zoom: z, north: lat(y0).toFixed(4), south: lat(y0 + h).toFixed(4), west: lon(x0).toFixed(4),
    east: lon(x0 + w).toFixed(4), tone: night ? 'night' : 'day' });
  try {
    const r = await (await fetch('/api/maps/attribution?' + q)).json();
    const text = r.copyright || 'Map data © Google';
    if (el.id !== 'thumb') setCredit('Google Maps · ' + text);
    else $('#thumb-meta').dataset.credit = text;
  } catch (e) { /* the logo alone until it answers */ }
}

function setCredit(map) {
  $('#radar-credit').textContent = 'Radar: ' + radarSource().credit + ' · Map: ' + map;
  $('#trip-credit').textContent = 'Routes and map: ' + map;
}
const loaded = (img) => new Promise((ok) => { img.onload = img.onerror = () => ok(img); });

/**
 * The radar that covers home: Environment Canada's composite in Canada (the rain or the snow layer), NOAA's mosaic for
 * the US region (lower 48, Alaska, Hawaii, Caribbean). GeoMet doesn't reach the Alaska panhandle, NOAA doesn't do Canada.
 */
function radarSource() {
  if (LOC.country !== 'US') {
    const snow = !!weather && (weather.temp <= 0.5 || weather.symbol === 'snowflake');
    const layer = snow ? RADAR.snow : RADAR.rain;
    return { base: GEOMET, layer, label: snow ? 'Snow' : 'Rain', legend: snow ? 'Snow (cm/h)' : 'Rain (mm/h)',
      credit: 'ECCC MSC GeoMet', legendArgs: '&SLD_VERSION=1.1.0' };
  }
  const region = LOC.lat > 51 && LOC.lon < -129 ? 'alaska' : LOC.lat < 23 && LOC.lon < -150 ? 'hawaii'
    : LOC.lat < 20 && LOC.lon > -68 ? 'caribbean' : 'conus';
  const layer = region + '_base_reflectivity_mosaic';
  return { base: NOAA + 'wms', caps: NOAA + layer + '/ows', layer, label: 'Radar', legend: 'Reflectivity (dBZ)',
    credit: 'NOAA nowCOAST', legendArgs: '' };
}

function wms(src, geo, time, scale) {
  return src.base + '?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=' + src.layer + '&STYLES=&CRS=EPSG:3857&BBOX=' + geo.bbox +
    '&WIDTH=' + Math.round(geo.w * scale) + '&HEIGHT=' + Math.round(geo.h * scale) + '&FORMAT=image/png&TRANSPARENT=TRUE&TIME=' + time;
}

let timesCache = null;

/**
 * The last hour of radar times, from the service's own time dimension: GeoMet gives a range in 6-minute steps, NOAA a
 * list (a frame about every 4 minutes). An empty list when the service can't be reached.
 */
async function radarTimes(src) {
  if (timesCache && timesCache.layer === src.layer && Date.now() - timesCache.at < 3 * 60000) return timesCache.times;
  let times = [];
  try {
    if (src.caps) {
      const x = await (await fetch(src.caps + '?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities')).text();
      const all = /<Dimension name="time"[^>]*>([^<]+)/.exec(x)[1].split(',').map((t) => t.trim());
      const end = Date.parse(all[all.length - 1]);
      times = all.filter((t) => Date.parse(t) >= end - 3600000);
    } else {
      const r = await fetch(GEOMET + '?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities&LAYER=' + src.layer);
      const end = Date.parse(/<Dimension name="time"[^>]*>[^/<]+\/([^/<]+)\/PT(\d+)M/.exec(await r.text())[1]);
      for (let i = 10; i >= 0; i--) times.push(new Date(end - i * 360000).toISOString().replace('.000', ''));
    }
  } catch (e) {
    console.warn('[mockup] radar times from ' + src.credit + ': ' + e.message);
  }
  if (times.length) timesCache = { layer: src.layer, times, at: Date.now() };
  return times;
}

let thumbDone = false;
async function loadThumb() {
  if (thumbDone) return;
  thumbDone = true;
  const el = $('#thumb'), geo = buildMap(el, 160, 211, 7, LOC, isNight());
  const src = radarSource(), times = await radarTimes(src);
  if (!times.length) { $('#thumb-meta').textContent = src.credit + " isn't answering"; return; }
  const img = new Image();
  img.className = 'frame on';
  img.style.cssText = 'left:0;top:0;width:160px;height:211px';
  img.decoding = 'async';
  img.src = wms(src, geo, times[times.length - 1], 2);
  el.appendChild(img);
  await loaded(img);
  const meta = $('#thumb-meta');
  meta.textContent = src.label + ' · ' + fmt.time.format(new Date(times[times.length - 1])) +
    (meta.dataset.credit ? ' · ' + meta.dataset.credit : '');
}

const radar = { timer: 0, frames: [], index: 0, playing: true, times: [] };

async function openRadar() {
  openLayer('radar');
  const t0 = performance.now(), map = $('#radar-map'), src = radarSource();
  const geo = buildMap(map, 960, 540, 8, LOC, isNight());
  $('#radar-status').textContent = 'Loading radar…';
  $('#radar-ago').textContent = src.label + ' · loading';
  $('#radar-legend').innerHTML = '<div>' + src.legend + '</div><img alt="" style="display:block;max-height:150px;margin-top:6px" src="' +
    src.base + '?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetLegendGraphic&LAYER=' + src.layer + '&FORMAT=image/png' + src.legendArgs + '">';
  const times = await radarTimes(src);
  if (layerName !== 'radar') return;
  if (!times.length) {
    $('#radar-status').textContent = src.credit + " isn't answering right now. Try again in a minute.";
    $('#radar-ago').textContent = src.label + ' · unavailable';
    return;
  }
  radar.times = times;
  radar.frames = times.map(() => { const i = new Image(); i.className = 'frame'; i.style.cssText = 'left:0;top:0;width:960px;height:540px'; i.decoding = 'async'; i.alt = ''; map.appendChild(i); return i; });
  $('#radar-frames').innerHTML = times.map(() => '<i></i>').join('');
  // The newest frame first, with the map: it alone makes the screen useful; the rest of the hour streams in after.
  const last = radar.frames.length - 1;
  radar.frames[last].fetchPriority = 'high';
  radar.frames[last].src = wms(src, geo, times[last], 1);
  await Promise.all([loaded(radar.frames[last]), ...geo.loads]);
  if (layerName !== 'radar') return;
  const ready = (performance.now() - t0) / 1000;
  showFrame(last);
  $('#radar-status').textContent = 'Ready in ' + ready.toFixed(1) + ' s';
  console.info('[mockup] radar ready in ' + ready.toFixed(2) + ' s');
  radar.frames[last].dataset.ok = 1;
  $('#radar-frames').children[last].classList.add('loaded');
  radar.frames.slice(0, last).forEach((img, i) => {
    img.src = wms(src, geo, times[i], 1);
    loaded(img).then(() => { img.dataset.ok = 1; const bar = $('#radar-frames').children[i]; if (bar) bar.classList.add('loaded'); });
  });
  radar.playing = true;
  schedule();
}

function showFrame(i) {
  radar.frames.forEach((f, j) => f.classList.toggle('on', j === i));
  [...$('#radar-frames').children].forEach((b, j) => b.classList.toggle('on', j === i));
  radar.index = i;
  const t = new Date(radar.times[i]), ago = Math.round((Date.now() - t) / 60000);
  $('#radar-time').textContent = fmt.time.format(t);
  $('#radar-ago').textContent = radarSource().label + ' · ' + (i === radar.frames.length - 1 ? 'latest, ' : '') + ago + ' min ago';
}

function schedule() {
  clearTimeout(radar.timer);
  if (!radar.playing) return;
  const last = radar.frames.length - 1;
  radar.timer = setTimeout(() => {
    let next = radar.index >= last ? 0 : radar.index + 1;
    while (next < last && !radar.frames[next].dataset.ok) next++;   // skip frames still loading
    showFrame(next);
    schedule();
  }, radar.index === last ? 1500 : 450);
}

function stepRadar(d) {
  if (!radar.frames.length) return;
  radar.playing = false;
  clearTimeout(radar.timer);
  showFrame(Math.max(0, Math.min(radar.frames.length - 1, radar.index + d)));
  $('#radar-status').textContent = 'Paused';
}

function closeRadar() {
  clearTimeout(radar.timer);
  radar.frames = [];
  $('#radar-map').textContent = '';   // let the frames go: they're ~11 full-screen images
}

// ---------- Plan a route (Google Places search, then Google Routes with live traffic from home) ----------

const duration = (s) => (s >= 3600 ? Math.floor(s / 3600) + ' h ' + Math.round((s % 3600) / 60) + ' min' : Math.max(1, Math.round(s / 60)) + ' min');

/** How much traffic adds, in Google Maps' colors: green (under 10%), orange (under 30%), red. */
function traffic(t) {
  const delay = t.duration - t.static, ratio = t.static ? delay / t.static : 0;
  return { color: ratio < 0.1 ? '#30D158' : ratio < 0.3 ? '#FF9F0A' : '#FF453A',
    text: delay >= 120 ? '+' + duration(delay) + ' from traffic' : 'No traffic delays' };
}

/** Google's encoded polyline (the Routes API's path) as [lat, lon] points. */
function decodePolyline(str) {
  const points = [];
  let i = 0, lat = 0, lon = 0;
  while (i < str.length) {
    for (const axis of [0, 1]) {
      let shift = 0, result = 0, b;
      do { b = str.charCodeAt(i++) - 63; result |= (b & 31) << shift; shift += 5; } while (b >= 32);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (axis === 0) lat += delta; else lon += delta;
    }
    points.push([lat / 1e5, lon / 1e5]);
  }
  return points;
}

/** Straight-line km between two places, to show which match is nearest before asking Google for a route. */
function km(a, b) {
  const r = Math.PI / 180, x = Math.sin((b.lat - a.lat) * r / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin((b.lon - a.lon) * r / 2) ** 2;
  return 12742 * Math.asin(Math.sqrt(x));
}

const PANEL = 360;   // the map's open area starts right of the panel
const plan = { places: [], route: null, asking: null };

/**
 * Lays the map so `points` ([lat, lon]) fit the open area right of the panel at the largest zoom that holds them all;
 * returns buildMap's answer plus a function from [lat, lon] to screen x, y.
 */
function fitMap(points) {
  const room = { w: 960 - PANEL - 70, h: 540 - 110 };
  let z = 15;
  for (; z > 3; z--) {
    const px = points.map(([la, lo]) => project(la, lo, z)), xs = px.map((p) => p.x), ys = px.map((p) => p.y);
    if (Math.max(...xs) - Math.min(...xs) <= room.w && Math.max(...ys) - Math.min(...ys) <= room.h) break;
  }
  const px = points.map(([la, lo]) => project(la, lo, z)), xs = px.map((p) => p.x), ys = px.map((p) => p.y);
  const cx = (Math.min(...xs) + Math.max(...xs)) / 2, cy = (Math.min(...ys) + Math.max(...ys)) / 2;
  const middle = PANEL + (960 - PANEL) / 2;   // the points' center lands in the middle of the open area
  const geo = buildMap($('#plan-map'), 960, 540, z, unproject(cx - (middle - 480), cy, z), false);
  geo.at = ([la, lo]) => { const p = project(la, lo, z); return { x: p.x - geo.x0, y: p.y - geo.y0 }; };
  return geo;
}

function openPlan() {
  plan.places = [];
  plan.route = null;
  $('#plan-q').value = '';
  $('#plan-home').textContent = LOC.name;
  $('#plan-svg').innerHTML = '';
  $('#plan-body').innerHTML = '<div class="plan-hint">Press OK on the mic and say a place, like “Costco” or “Wasaga Beach”. Or press OK on the box to type.</div>';
  lastFocus.plan = $('#plan-mic');
  openLayer('plan');
  fitMap([[LOC.lat, LOC.lon], [LOC.lat + 0.05, LOC.lon + 0.08], [LOC.lat - 0.05, LOC.lon - 0.08]]);   // home, a few km around
}

/** Voice first (Android's SpeechRecognizer on the TV, the browser's here); typing when there's no microphone. */
function planVoice() {
  if (!listen((text) => { $('#plan-q').value = text; searchPlaces(text); })) {
    setFocus($('#plan-field'));
    startEditing($('#plan-q'));
    toast('No speech recognition in this browser: type the place, then press Enter.');
  }
}

async function searchPlaces(text) {
  text = text.trim();
  if (!text) return;
  plan.route = null;
  $('#plan-svg').innerHTML = '';
  $('#plan-body').innerHTML = '<div class="status" style="margin:4px">Searching Google Maps for “' + esc(text) + '”…</div>' +
    '<div class="shimmer"></div><div class="shimmer" style="width:85%"></div><div class="shimmer" style="width:70%"></div>';
  const ask = plan.asking = {};
  let d;
  try {
    d = await (await fetch('/api/places?q=' + encodeURIComponent(text))).json();
  } catch (e) {
    d = { error: "The mockup server isn't running." };
  }
  if (plan.asking !== ask || layerName !== 'plan') return;
  if (d.error) return planError(d.error);
  plan.places = d.places;
  if (!plan.places.length) return planError('Google Maps found nothing for “' + text + '”. Try another name.');
  const home = { lat: LOC.lat, lon: LOC.lon };
  $('#plan-body').innerHTML = plan.places.map((p, i) =>
    '<div class="row result" data-f data-action="plan-pick" data-i="' + i + '"><span class="n">' + (i + 1) + '</span><div class="result-main">' +
    '<div class="result-name">' + esc(p.name) + '</div><div class="result-addr">' + esc(p.address) + '</div></div>' +
    '<span class="result-km num">' + km(home, p).toFixed(km(home, p) < 10 ? 1 : 0) + ' km</span></div>').join('');
  drawPins(-1);
  focused = null;
  setFocus(document.querySelector('#plan-body .result'));
}

/** The matches as numbered pins, fitted with home; the focused one in blue. */
function drawPins(on) {
  const geo = plan.pinsGeo = plan.pinsGeo && plan.pinsFor === plan.places ? plan.pinsGeo
    : fitMap([[LOC.lat, LOC.lon], ...plan.places.map((p) => [p.lat, p.lon])]);
  plan.pinsFor = plan.places;
  $('#plan-svg').innerHTML = plan.places.map((p, i) => {
    const pt = geo.at([p.lat, p.lon]);
    return '<g class="pin' + (i === on ? ' on' : '') + '" transform="translate(' + pt.x.toFixed(1) + ' ' + pt.y.toFixed(1) + ')"><circle r="' + (i === on ? 11 : 9) + '"/><text>' + (i + 1) + '</text></g>';
  }).join('');
}

async function pickPlace(i) {
  const p = plan.places[i];
  $('#plan-body').innerHTML = '<div class="route-card"><div class="route-name">' + esc(p.name) + '</div><div class="route-addr">' + esc(p.address) + '</div>' +
    '<div class="status" style="margin-top:14px">Finding the drive with live traffic…</div><div class="shimmer" style="width:60%"></div></div>';
  const ask = plan.asking = {};
  let d;
  try {
    d = await (await fetch('/api/route?place=' + encodeURIComponent(p.id))).json();
  } catch (e) {
    d = { error: "The mockup server isn't running." };
  }
  if (plan.asking !== ask || layerName !== 'plan') return;
  if (d.error) return planError(d.error, true);
  plan.route = { place: p, ...d };
  const tr = traffic(d);
  $('#plan-body').innerHTML = '<div class="route-card"><div class="route-name">' + esc(p.name) + '</div><div class="route-addr">' + esc(p.address) + '</div>' +
    '<div class="route-time num">' + duration(d.duration) + '</div>' +
    '<div class="route-traffic"><i style="background:' + tr.color + '"></i>' + tr.text + '</div>' +
    '<div class="route-facts">' + (d.meters / 1000).toFixed(d.meters < 10000 ? 1 : 0) + ' km' + (d.via ? ' · via ' + esc(d.via) : '') +
    '<br>Arrive about ' + fmt.time.format(new Date(Date.now() + d.duration * 1000)) + ' if you leave now</div></div>' +
    '<div class="row" data-f data-action="plan-back" style="margin-top:14px"><span>Back to the matches</span></div>';
  $('#plan-foot').textContent = 'Live traffic · ' + fmt.time.format(new Date());
  const pts = decodePolyline(d.polyline), geo = fitMap(pts), path = pts.map((q, j) => { const s2 = geo.at(q); return (j ? 'L' : 'M') + s2.x.toFixed(1) + ' ' + s2.y.toFixed(1); }).join('');
  const end = geo.at(pts[pts.length - 1]);
  plan.pinsGeo = null;
  $('#plan-svg').innerHTML = '<path d="' + path + '" class="casing"/><path d="' + path + '" class="line"/>' +
    '<g class="pin" transform="translate(' + end.x.toFixed(1) + ' ' + end.y.toFixed(1) + ')"><circle r="9"/></g>';
  focused = null;
  setFocus(document.querySelector('#plan-body [data-action="plan-back"]'));
}

function planError(msg, keepPlaces) {
  $('#plan-body').innerHTML = '<div class="err" style="margin:4px;font-size:13px;line-height:19px"><span class="badge">!</span><div>' + esc(msg) + '</div></div>' +
    (keepPlaces && plan.places.length ? '<div class="row" data-f data-action="plan-back" style="margin-top:12px"><span>Back to the matches</span></div>' : '');
  focused = null;
  setFocus(document.querySelector('#plan-body [data-f]') || $('#plan-mic'));
}

/** Back from a route shows the matches again; from the matches, Back closes the planner. */
function planBack() {
  if (!plan.route && !document.querySelector('#plan-body [data-action="plan-back"]')) return false;
  plan.route = null;
  const places = plan.places;
  plan.places = [];
  plan.pinsGeo = null;
  if (!places.length) return false;
  plan.places = places;
  $('#plan-body').innerHTML = places.map((p, i) =>
    '<div class="row result" data-f data-action="plan-pick" data-i="' + i + '"><span class="n">' + (i + 1) + '</span><div class="result-main">' +
    '<div class="result-name">' + esc(p.name) + '</div><div class="result-addr">' + esc(p.address) + '</div></div>' +
    '<span class="result-km num">' + km(LOC, p).toFixed(km(LOC, p) < 10 ? 1 : 0) + ' km</span></div>').join('');
  drawPins(-1);
  focused = null;
  setFocus(document.querySelector('#plan-body .result'));
  return true;
}

// ---------- Shelf ----------

let shelf = store.get('shelf', ['youtube', 'netflix', 'prime', 'disney', 'crave']).filter((k) => APPS.some((a) => a.key === k)).slice(0, MAX_APPS);

function renderShelf() {
  const n = shelf.length, gap = 24, pad = 20;
  const tw = Math.min(135, (880 - Math.max(0, n - 1) * gap) / Math.max(1, n));
  const width = n * tw + Math.max(0, n - 1) * gap, left = (960 - width) / 2;
  const platter = $('#shelf .platter');
  platter.style.left = (left - pad) + 'px';
  platter.style.width = (width + 2 * pad) + 'px';
  platter.hidden = n === 0;
  const keep = focused && focused.classList.contains('tile') ? focused.dataset.key : null;
  $('#tiles').innerHTML = shelf.map((k, i) => {
    const a = APPS.find((x) => x.key === k);
    return '<div class="tile" data-f data-action="launch" data-key="' + k + '" style="left:' + (left + i * (tw + gap)) + 'px;width:' + tw + 'px;--a:' + a.a + ';--b:' + a.b + '">' +
      '<div class="bg"></div><div class="logo ' + k + '">' + a.logo + '</div><div class="tile-name">' + esc(a.name) + '</div></div>';
  }).join('');
  if (keep) setFocus(document.querySelector('.tile[data-key="' + keep + '"]') || document.querySelector('.tile'));
}

// ---------- Focus, like a TV remote ----------

let layerName = 'home', focused = null, editing = null;
const lastFocus = {};

function focusables() {
  return [...document.querySelectorAll('[data-layer="' + layerName + '"] [data-f]')].filter((el) => el.getClientRects().length);
}

function setFocus(el) {
  if (!el || el === focused) return;
  if (focused) focused.classList.remove('focus');
  focused = el;
  el.classList.add('focus');
  lastFocus[layerName] = el;
  showClock();
  if (el.id === 'card-weather' || el.id === 'card-radar') idle(() => radarTimes(radarSource()));   // warm the radar's time list
  if (layerName === 'settings') keepInView(el);
  if (el.classList.contains('result') && plan.places.length) drawPins(+el.dataset.i);   // its pin turns blue
}

function center(el) {
  const r = el.getBoundingClientRect();
  return { x: r.left + r.width / 2, y: r.top + r.height / 2, l: r.left, r: r.right, t: r.top, b: r.bottom };
}

/** Android-style focus search: the nearest item in that direction, staying in line when possible. */
function move(dir) {
  const list = focusables();
  if (!focused || !list.includes(focused)) return setFocus(list[0]);
  const a = center(focused);
  let best = null, score = Infinity;
  for (const el of list) {
    if (el === focused) continue;
    const b = center(el);
    let along, across;
    if (dir === 'left') { if (b.x >= a.x - 1) continue; along = Math.max(0, a.l - b.r); across = Math.abs(b.y - a.y) * 2; }
    if (dir === 'right') { if (b.x <= a.x + 1) continue; along = Math.max(0, b.l - a.r); across = Math.abs(b.y - a.y) * 2; }
    if (dir === 'up') { if (b.y >= a.y - 1) continue; along = Math.max(0, a.t - b.b); across = Math.max(0, Math.abs(b.x - a.x) - (b.r - b.l) / 2); }
    if (dir === 'down') { if (b.y <= a.y + 1) continue; along = Math.max(0, b.t - a.b); across = Math.max(0, Math.abs(b.x - a.x) - (b.r - b.l) / 2); }
    const s = along + across;
    if (s < score) { score = s; best = el; }
  }
  if (best) setFocus(best);
}

function openLayer(name) {
  if (layerName !== 'home') closeLayer(true);
  layerName = name;
  $('#' + name).hidden = false;
  const first = lastFocus[name] && lastFocus[name].isConnected ? lastFocus[name] : focusables()[0];
  if (focused) focused.classList.remove('focus');
  focused = null;
  setFocus(first);
}

function closeLayer(quiet) {
  if (layerName === 'home') return;
  if (layerName === 'radar') closeRadar();
  if (layerName === 'plan') { $('#plan-map').textContent = ''; plan.asking = null; plan.pinsGeo = null; }
  if (layerName === 'answer' && asking) asking.abort();
  stopEditing(false);
  $('#' + layerName).hidden = true;
  layerName = 'home';
  if (quiet) return;
  if (focused) focused.classList.remove('focus');
  focused = null;
  setFocus(lastFocus.home || focusables()[0]);
}

// ---------- Text fields ----------

function startEditing(input, row) {
  editing = { input, row, before: input.value };
  (row || input.closest('.field')).classList.add('editing');
  input.hidden = false;
  input.focus();
  input.setSelectionRange(input.value.length, input.value.length);
}

function stopEditing(keep) {
  if (!editing) return;
  const { input, row, before } = editing;
  if (!keep) input.value = before;
  (row || input.closest('.field')).classList.remove('editing');
  input.blur();
  if (row) input.hidden = true;
  editing = null;
}

// ---------- Ask AI ----------

let settings = { hasKey: false, keyHint: '', model: 'gemini-flash-latest', grounding: true };
let asking = null;

async function loadSettings() {
  try {
    settings = await (await fetch('/api/settings')).json();
  } catch (e) { /* server not running: Ask AI says so when used */ }
  renderSettings();
}

async function askAI(q) {
  q = q.trim();
  openLayer('answer');
  $('#ai-q').textContent = q;
  $('#ai-model').textContent = settings.model;
  $('#ai-sources').innerHTML = '';
  $('#ai-actions').innerHTML = '';
  if (!settings.hasKey) return noKey();
  $('#ai-body').innerHTML = '<div class="status">' + (settings.grounding ? 'Searching Google and writing an answer…' : 'Writing an answer…') + '</div>' +
    '<div class="shimmer" style="width:92%"></div><div class="shimmer" style="width:80%"></div><div class="shimmer" style="width:64%"></div>';
  const ctrl = new AbortController(), t0 = performance.now();
  if (asking) asking.abort();
  asking = ctrl;
  let res;
  try {
    const r = await fetch('/api/ask', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ q }), signal: ctrl.signal });
    res = await r.json();
  } catch (e) {
    if (ctrl.signal.aborted) return;
    res = { error: "Couldn't reach the mockup server. Is mockup/server.py running?" };
  }
  if (asking !== ctrl) return;
  asking = null;
  if (res.error === 'no_key') return noKey();
  if (res.error) return showError(res.error, q);
  console.info('[mockup] answer in ' + Math.round(performance.now() - t0) + ' ms, ' + res.sources.length + ' sources');
  renderAnswer(res);
}

function renderAnswer(res) {
  const lines = res.text.split('\n').map((l) => l.trim()).filter(Boolean);
  let html = '', list = false;
  lines.forEach((l, i) => {
    const bullet = /^[-*•]\s+/.test(l);
    if (bullet && !list) { html += '<ul>'; list = true; }
    if (!bullet && list) { html += '</ul>'; list = false; }
    const text = esc(l.replace(/^[-*•]\s+/, '').replace(/\*\*(.+?)\*\*/g, '$1'));
    html += bullet ? '<li>' + text + '</li>' : '<p' + (i === 0 ? ' class="lead"' : '') + '>' + text + '</p>';
  });
  if (list) html += '</ul>';
  $('#ai-body').innerHTML = '<div class="inner">' + html + '</div>';
  if (res.model) $('#ai-model').textContent = res.model;
  $('#ai-sources').innerHTML = res.sources.length
    ? '<div class="lbl">Sources</div>' + res.sources.map((s, i) => '<button class="chip" data-f data-action="source" data-uri="' + esc(s.uri) + '"><span class="n">' + (i + 1) +
      '</span><span>' + esc(s.title) + '</span></button>').join('')
    : (settings.grounding ? '<div class="lbl">No web sources for this answer</div>' : '');
  focused = null;
  setFocus(document.querySelector('#ai-sources .chip') || $('#ai-body'));
  scrollAnswer(0, true);
}

function noKey() {
  $('#ai-body').innerHTML = '<div class="err"><span class="badge">!</span><div>Ask AI needs a Gemini API key.<br><span class="status">Add one in Settings (or from your laptop with one adb command), then ask again.</span></div></div>';
  $('#ai-actions').innerHTML = '<button class="chip" data-f data-action="open-settings"><span>Open Settings</span></button>';
  focused = null;
  setFocus(document.querySelector('#ai-actions .chip'));
}

function showError(msg, q) {
  $('#ai-body').innerHTML = '<div class="err"><span class="badge">!</span><div>' + esc(msg) + '</div></div>';
  $('#ai-actions').innerHTML = '<button class="chip" data-f data-action="retry" data-q="' + esc(q) + '"><span>Try again</span></button>' +
    '<button class="chip" data-f data-action="open-settings"><span>Settings</span></button>';
  focused = null;
  setFocus(document.querySelector('#ai-actions .chip'));
}

let answerY = 0;
/** Up/Down on the answer scroll it; at either end, the focus moves on. Returns false at the end. */
function scrollAnswer(d, reset) {
  const body = $('#ai-body'), inner = body.querySelector('.inner');
  if (!inner) return false;
  if (reset) answerY = 0;
  const max = Math.max(0, inner.scrollHeight - body.clientHeight);
  const y = Math.max(0, Math.min(max, answerY + d));
  if (!reset && y === answerY) return false;
  answerY = y;
  inner.style.transform = 'translateY(' + -y + 'px)';
  return true;
}

// ---------- Voice ----------

function listen(then) {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) return false;
  const rec = new SR();
  rec.lang = 'en-CA';
  rec.interimResults = true;
  let text = '';
  $('#listening').hidden = false;
  $('#listen-text').textContent = 'Listening… ask anything';
  rec.onresult = (e) => { text = [...e.results].map((r) => r[0].transcript).join(''); $('#listen-text').textContent = text; };
  rec.onerror = (e) => { toast(e.error === 'not-allowed' ? 'Microphone blocked. Allow it in the browser to try voice.' : 'Voice: ' + e.error); };
  rec.onend = () => { $('#listening').hidden = true; listening = null; if (text.trim()) then(text); };
  listening = rec;
  rec.start();
  return true;
}
let listening = null;

// ---------- Settings ----------

function renderSettings() {
  $('#set-key').textContent = settings.hasKey ? settings.keyHint : 'Not set';
  $('#set-model').textContent = settings.model;
  $('#set-postal').textContent = settings.postal || 'Not set';
  const m = settings.maps;
  $('#set-maps').textContent = m && m.hasKey ? MAP_NAMES[m.provider] + ' · ' + m.keyHint : 'Free map (no key)';
  $('#set-grounding').classList.toggle('on', settings.grounding);
  $('#set-count').textContent = shelf.length + ' of ' + MAX_APPS;
  const keep = focused && focused.dataset.action === 'pick' ? focused.dataset.key : null;
  $('#set-apps').innerHTML = APPS.map((a) => {
    const i = shelf.indexOf(a.key);
    return '<div class="row app-row" data-f data-action="pick" data-key="' + a.key + '"><span class="name"><span class="swatch" style="--a:' + a.a + ';--b:' + a.b + '"></span>' +
      esc(a.name) + '</span><span class="pick' + (i >= 0 ? ' on' : '') + '">' + (i >= 0 ? i + 1 : '') + '</span></div>';
  }).join('');
  if (keep) { focused = null; setFocus(document.querySelector('.app-row[data-key="' + keep + '"]')); }
}

async function saveSettings(change) {
  try {
    settings = await (await fetch('/api/settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(change) })).json();
  } catch (e) {
    toast("Couldn't save: the mockup server isn't running.");
  }
  renderSettings();
}

async function testConnection() {
  const out = $('#set-test');
  out.className = 'val';
  if (!settings.hasKey) { out.textContent = 'Add a key first'; out.classList.add('bad'); return; }
  out.textContent = 'Testing…';
  let res;
  try {
    res = await (await fetch('/api/test', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).json();
  } catch (e) {
    res = { ok: false, message: "The mockup server isn't running." };
  }
  out.textContent = res.message || (res.error === 'no_key' ? 'Add a key first' : res.error);
  out.title = out.textContent;
  out.classList.add(res.ok ? 'ok' : 'bad');
}

function keepInView(el) {
  const list = $('#set-list');
  let sc = list.querySelector('.scroller');
  if (!sc) {   // wrap once, so the list scrolls with a transform (no layout per frame)
    sc = document.createElement('div');
    sc.className = 'scroller';
    while (list.firstChild) sc.appendChild(list.firstChild);
    list.appendChild(sc);
  }
  const y = el.offsetTop, h = list.clientHeight, cur = -(+sc.dataset.y || 0);
  let next = cur;
  if (y - 60 < cur) next = Math.max(0, y - 60);
  if (y + 100 > cur + h) next = y + 100 - h;
  sc.dataset.y = -next;
  sc.style.transform = 'translateY(' + -next + 'px)';
}

// ---------- Toast ----------

let toastTimer = 0;
function toast(text) {
  const t = $('#toast');
  t.textContent = text;
  t.hidden = false;
  t.style.animation = 'none';
  void t.offsetWidth;
  t.style.animation = '';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, TOAST_MS);
}

// ---------- OK ----------

function activate() {
  const el = focused;
  if (!el) return;
  const q = $('#q');
  switch (el.dataset.action) {
    case 'type': startEditing(q); break;
    case 'mic': toast('On your Sony, the mic opens Google TV voice search (the system assistant). Ask AI listens through its own button.'); break;
    case 'ask':
      if (q.value.trim()) askAI(q.value);
      else if (!listen((t) => { q.value = t; askAI(t); })) { setFocus($('#field')); startEditing(q); toast('Type a question, then press Enter.'); }
      break;
    case 'tv-settings': toast("On the TV this opens Sony's own Settings (picture, sound, network)."); break;
    case 'profile': toast('On the TV this opens your Google TV profiles. Phase 1 checks whether the Sony lets a launcher do that.'); break;
    case 'settings': case 'open-settings': openLayer('settings'); break;
    case 'radar': openRadar(); break;
    case 'plan': openPlan(); break;
    case 'plan-voice': planVoice(); break;
    case 'plan-type': startEditing($('#plan-q')); break;
    case 'plan-pick': pickPlace(+el.dataset.i); break;
    case 'plan-back': planBack(); break;
    case 'edit-postal': $('#in-postal').value = settings.postal || ''; startEditing($('#in-postal'), el); $('#set-postal').hidden = true; break;
    case 'launch': toast('Opens ' + APPS.find((a) => a.key === el.dataset.key).name + ' on the TV.'); break;
    case 'source': window.open(el.dataset.uri, '_blank', 'noopener'); break;
    case 'retry': askAI(el.dataset.q); break;
    case 'radar-toggle':
      radar.playing = !radar.playing;
      $('#radar-status').textContent = radar.playing ? 'Playing' : 'Paused';
      schedule();
      break;
    case 'edit-key': $('#in-key').value = ''; startEditing($('#in-key'), el); $('#set-key').hidden = true; break;
    case 'edit-model': $('#in-model').value = settings.model; startEditing($('#in-model'), el); $('#set-model').hidden = true; break;
    case 'toggle-grounding': saveSettings({ grounding: !settings.grounding }); break;
    case 'test': testConnection(); break;
    case 'pick': {
      const k = el.dataset.key, i = shelf.indexOf(k);
      if (i >= 0) shelf.splice(i, 1);
      else if (shelf.length >= MAX_APPS) { toast('Up to ' + MAX_APPS + ' apps. Turn one off first.'); break; }
      else shelf.push(k);
      store.set('shelf', shelf);
      renderShelf();
      renderSettings();
      break;
    }
    case 'revert': toast("On the TV this switches back to Sony's home screen (Original) and turns its apps back on."); break;
  }
}

function commitEdit() {
  const { input } = editing;
  if (input.id === 'plan-q') {
    const text = input.value;
    stopEditing(true);
    searchPlaces(text);
    return;
  }
  if (input.id === 'q') {
    const text = input.value;
    stopEditing(true);
    // Typed text goes to Ask AI here; on the TV, Phase 3 tries Google TV search first.
    if (text.trim()) askAI(text);
    return;
  }
  const value = input.value.trim();
  stopEditing(false);
  showValues();
  if (!value) return;
  if (input.id === 'in-key') saveSettings({ key: value }).then(() => { input.value = ''; });
  if (input.id === 'in-model') saveSettings({ model: value });
  if (input.id === 'in-postal') savePostal(value);
}

function showValues() {
  for (const id of ['#set-key', '#set-model', '#set-postal']) $(id).hidden = false;
}

/** A new postal code moves home: the server looks it up, then weather, radar and trips follow. */
async function savePostal(value) {
  try {
    const r = await fetch('/api/settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ postal: value }) });
    const d = await r.json();
    if (d.error) return toast(d.error);
    settings = d;
  } catch (e) {
    return toast("Couldn't save: the mockup server isn't running.");
  }
  renderSettings();
  await loadHome();
  toast('Home is now ' + LOC.name + '.');
  thumbDone = false;
  timesCache = null;
  loadWeather().then(() => idle(loadThumb));
}

// ---------- Keys: arrows, Enter = OK, Esc = Back ----------

addEventListener('keydown', (e) => {
  const k = e.key;
  if (editing) {
    if (k === 'Enter') { e.preventDefault(); commitEdit(); }
    else if (k === 'Escape') { e.preventDefault(); stopEditing(false); showValues(); }
    else if (k === 'ArrowUp' || k === 'ArrowDown') { e.preventDefault(); const keep = editing.input.id === 'q' || editing.input.id === 'plan-q'; stopEditing(keep); showValues(); move(k === 'ArrowUp' ? 'up' : 'down'); }
    return;
  }
  if (k.startsWith('Arrow')) {
    e.preventDefault();
    const dir = k.slice(5).toLowerCase();
    if (layerName === 'radar') { if (dir === 'left' || dir === 'right') stepRadar(dir === 'left' ? -1 : 1); return; }
    if (layerName === 'answer' && focused && focused.id === 'ai-body' && (dir === 'up' || dir === 'down') && scrollAnswer(dir === 'up' ? -112 : 112)) return;
    if (layerName === 'answer' && dir === 'up' && focused && focused.classList.contains('chip') && $('#ai-body .inner')) { setFocus($('#ai-body')); return; }
    move(dir);
  } else if (k === 'Enter' || k === ' ') {
    e.preventDefault();
    activate();
  } else if (k === 'Escape' || k === 'Backspace') {
    e.preventDefault();
    if (listening) listening.abort();
    else if (!(layerName === 'plan' && planBack())) closeLayer();
  } else if (k === 'n' || k === 'N') {
    nightOverride = !isNight();
    tick();
    toast(nightOverride ? 'Night look (press N again for day)' : 'Day look');
  } else if (k === '2') {
    forceFit = !forceFit;
    fit();
  }
});

// ---------- Frame rate, outside the TV frame (helps judge the effects before Phase 2) ----------

let frames = 0, fpsAt = performance.now();
const fpsEl = document.createElement('span');
fpsEl.style.marginLeft = '12px';
$('#help').appendChild(fpsEl);
(function loop(now) {
  frames++;
  if (now - fpsAt >= 1000) { fpsEl.textContent = Math.round(frames * 1000 / (now - fpsAt)) + ' fps'; frames = 0; fpsAt = now; }
  requestAnimationFrame(loop);
})(performance.now());

// ---------- Start: draw first, then fetch ----------

renderShelf();
renderSettings();
tick();
setInterval(tick, 15000);
setFocus(document.querySelector('.tile') || $('#field'));
document.fonts.ready.then(() => document.body.classList.add('ready'));
idle(() => {
  const ready = loadSettings();
  // Home first: the weather, the radar and the trips all start from it.
  loadHome().then(() => {
    loadWeather().then(() => ready).then(() => idle(loadThumb));
  });
});
