// DCrux service: a small relay that keeps the Claude API key on the server.
//
// The app cannot hold a secret safely, so the public build talks to this relay instead of
// carrying a key. The relay only forwards the two calls the app makes, only for an allow-list
// of models, and counts requests per device, per address and overall so one install (or one
// person with curl) cannot run up the bill. No request or response text is logged or stored.
//
// The limits are set high enough that no real user meets them; they are circuit breakers
// against abuse, not a ration. Requests are streamed straight through, and connections to
// the API are kept open between requests, so the relay adds almost no waiting of its own.
//
// Node 18+, no dependencies. Configuration is by environment variables (see README.md).

import http from 'node:http';
import https from 'node:https';
import crypto from 'node:crypto';
import fs from 'node:fs';

const env = process.env;
const cfg = {
  port: Number(env.PORT || 8787),
  host: env.HOST || '127.0.0.1',
  apiKey: env.ANTHROPIC_API_KEY || '',
  appSecret: env.DCRUX_APP_SECRET || '',
  models: (env.DCRUX_MODELS || 'claude-opus-5-5,claude-sonnet-5-5,claude-haiku-5-5').split(',').map(s => s.trim()).filter(Boolean),
  maxTokens: Number(env.DCRUX_MAX_TOKENS || 32000),
  // Room for the photographed pages of one handwritten answer (up to 8 pictures).
  maxBody: Number(env.DCRUX_MAX_BODY_BYTES || 12_000_000),
  maxImages: Number(env.DCRUX_MAX_IMAGES || 8),
  // Web searches one answer may make (each search is billed by the API).
  maxSearches: Number(env.DCRUX_MAX_SEARCHES || 3),
  perDevice: Number(env.DCRUX_DEVICE_DAILY || 1000),
  // Mobile networks put thousands of phones behind one address, so this has to be generous.
  perAddress: Number(env.DCRUX_ADDRESS_DAILY || 20000),
  overall: Number(env.DCRUX_GLOBAL_DAILY || 100000),
  // Requests one install may have running at the same moment.
  concurrent: Number(env.DCRUX_DEVICE_CONCURRENT || 8),
  stateFile: env.DCRUX_STATE_FILE || '',
  // India's official air-quality monitors (CPCB), published on data.gov.in. Needs your own free key from
  // data.gov.in (the site's public sample key is rationed to a few dozen requests and cannot carry an app).
  // Without it the relay answers "not set up" and the app shows its own estimate instead.
  dataGovKey: env.DATAGOV_API_KEY || '',
  // Sync across a person's devices: where their encrypted data is kept. The relay cannot read it (the key
  // never leaves the devices); it only stores and returns it. Unset means sync is not offered.
  syncDir: env.DCRUX_SYNC_DIR || '',
  syncMaxTotal: Number(env.DCRUX_SYNC_MAX_TOTAL_BYTES || 2_000_000_000),
  syncWritesDaily: Number(env.DCRUX_SYNC_WRITES_DAILY || 600),
  upstream: env.DCRUX_UPSTREAM || 'https://api.anthropic.com',
};
if (!cfg.apiKey || !cfg.appSecret) {
  console.error('ANTHROPIC_API_KEY and DCRUX_APP_SECRET must be set');
  process.exit(1);
}

const ALLOWED_BODY_KEYS = new Set(['model', 'max_tokens', 'stream', 'system', 'messages', 'output_config', 'fallbacks', 'thinking', 'tools']);
const ALLOWED_BETAS = new Set(['server-side-fallback-2026-07-01']);

// ---- daily counters (India time, since that is where the users are) ----
const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
let counters = { day: today(), overall: 0, devices: {}, addresses: {} };
try {
  if (cfg.stateFile && fs.existsSync(cfg.stateFile)) {
    const saved = JSON.parse(fs.readFileSync(cfg.stateFile, 'utf8'));
    if (saved.day === today()) counters = saved;
  }
} catch { /* start fresh */ }
const roll = () => { if (counters.day !== today()) counters = { day: today(), overall: 0, devices: {}, addresses: {} }; };
const save = () => { if (cfg.stateFile) { try { fs.writeFileSync(cfg.stateFile, JSON.stringify(counters)); } catch { /* not fatal */ } } };
setInterval(save, 60_000).unref();
const short = v => crypto.createHash('sha256').update(v).digest('hex').slice(0, 16);

// Signatures already used, so a captured request cannot be sent a second time.
const seen = new Map();
setInterval(() => { const cut = Date.now() - 11 * 60_000; for (const [k, t] of seen) if (t < cut) seen.delete(k); }, 60_000).unref();
// Requests in flight per install.
const inflight = new Map();

function send(res, status, type, message, extra = {}) {
  const body = JSON.stringify({ type: 'error', error: { type, message } });
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), 'cache-control': 'no-store', 'x-content-type-options': 'nosniff', ...extra });
  res.end(body);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', c => {
      size += c.length;
      if (size > limit) { reject(Object.assign(new Error('too large'), { status: 413 })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/** Every call must carry the install's id, a fresh timestamp and a signature over both and the body. */
function checkCaller(req, body) {
  const device = String(req.headers['x-dcrux-device'] || '');
  const ts = Number(req.headers['x-dcrux-ts'] || 0);
  const sig = String(req.headers['x-dcrux-sig'] || '');
  if (!/^[A-Za-z0-9_-]{16,64}$/.test(device)) return null;
  if (!Number.isFinite(ts) || Math.abs(Date.now() - ts) > 10 * 60_000) return null;
  const bodyHash = crypto.createHash('sha256').update(body).digest('hex');
  const want = crypto.createHmac('sha256', cfg.appSecret).update(`${device}.${ts}.${bodyHash}`).digest('hex');
  if (sig.length !== want.length || !crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(want))) return null;
  if (seen.has(sig)) return null;
  seen.set(sig, Date.now());
  return device;
}

/** Only plain text and inline pictures may be sent; nothing that makes the API fetch or run anything. */
function checkMessages(messages) {
  let images = 0;
  for (const m of messages) {
    if (!m || (m.role !== 'user' && m.role !== 'assistant')) return 'Malformed request.';
    if (typeof m.content === 'string') continue;
    if (!Array.isArray(m.content)) return 'Malformed request.';
    for (const b of m.content) {
      if (b && b.type === 'text' && typeof b.text === 'string') continue;
      if (b && b.type === 'image' && b.source && b.source.type === 'base64' && typeof b.source.data === 'string' &&
          ['image/jpeg', 'image/png', 'image/webp'].includes(b.source.media_type)) { images++; continue; }
      return 'That kind of content is not available through this service.';
    }
  }
  return images > cfg.maxImages ? 'Too many pictures in one request.' : null;
}

function addressOf(req) {
  return String(req.headers['cf-connecting-ip'] || req.headers['x-real-ip'] || req.socket.remoteAddress || 'unknown');
}

// Connections to the API stay open between requests, which saves a TLS handshake on each one.
const agents = { 'https:': new https.Agent({ keepAlive: true, maxSockets: 256, maxFreeSockets: 32 }), 'http:': new http.Agent({ keepAlive: true, maxSockets: 256 }) };

function upstreamRequest(method, path, headers, body, onResponse, onError) {
  const u = new URL(cfg.upstream + path);
  const lib = u.protocol === 'http:' ? http : https;
  const up = lib.request({ method, hostname: u.hostname, port: u.port || undefined, path: u.pathname + u.search, headers, agent: agents[u.protocol] }, onResponse);
  up.on('error', onError);
  up.setTimeout(600_000, () => up.destroy(new Error('upstream timeout')));
  if (body) up.write(body);
  up.end();
  return up;
}

let modelsCache = { at: 0, body: null };

// How much encrypted sync data is stored, counted once at start and kept up to date after that.
let syncTotal = 0;
if (cfg.syncDir) {
  try { fs.mkdirSync(cfg.syncDir, { recursive: true, mode: 0o700 }); for (const f of fs.readdirSync(cfg.syncDir)) syncTotal += fs.statSync(`${cfg.syncDir}/${f}`).size; }
  catch (e) { console.error('sync folder not usable:', e.message); }
}

// ---- air quality from CPCB monitors -------------------------------------------------------
const AQI_RESOURCE = 'https://api.data.gov.in/resource/3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69';
const aqiCache = new Map();   // city (lower case) -> { at, data, pending }

function getJson(url, ms = 25_000) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { headers: { accept: 'application/json', 'user-agent': 'DCrux relay (https://github.com/thegeekygamechanger/DCrux)' } }, r => {
      const chunks = []; r.on('data', c => chunks.push(c));
      r.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (e) { reject(e); } });
    });
    req.on('error', reject);
    req.setTimeout(ms, () => req.destroy(new Error('timeout')));
  });
}

/** Every record the monitors of one city reported in the latest hour. The source is slow and sometimes fails, so it is tried twice. */
async function cityRecords(city) {
  const url = `${AQI_RESOURCE}?api-key=${cfg.dataGovKey}&format=json&limit=2000&filters%5Bcity%5D=${encodeURIComponent(city)}`;
  let d;
  try { d = await getJson(url); } catch { d = await getJson(url); }
  // A refusal (rate limit, bad key) comes back as JSON too; it must not be mistaken for "this city has no monitors".
  if (!Array.isArray(d.records)) throw new Error(String(d.error || d.message || 'no records field').slice(0, 80));
  return d.records;
}

const km = (a, b, c, d) => { const R = 6371, x = (c - a) * Math.PI / 180, y = (d - b) * Math.PI / 180, h = Math.sin(x / 2) ** 2 + Math.cos(a * Math.PI / 180) * Math.cos(c * Math.PI / 180) * Math.sin(y / 2) ** 2; return 2 * R * Math.asin(Math.sqrt(h)); };

/**
 * The city's index the way CPCB reports it: each station's index is its worst pollutant's
 * sub-index, and the city's is the average of its stations. Also the worst station, since
 * one number hides how bad the worst neighbourhood is.
 */
function summarise(records, lat, lon) {
  const stations = new Map();
  for (const r of records) {
    const v = Number(r.avg_value), peak = Number(r.max_value);
    if (!r.station || !Number.isFinite(v)) continue;
    const s = stations.get(r.station) || { name: String(r.station).replace(/\s*-\s*[A-Z]{3,6}$/, '').trim(), lat: Number(r.latitude), lon: Number(r.longitude), aqi: -1, dominant: '', peak: -1, hasPm: false, updated: r.last_update };
    if (v > s.aqi) { s.aqi = v; s.dominant = r.pollutant_id; }
    if (Number.isFinite(peak) && peak > s.peak) s.peak = peak;
    if (r.pollutant_id === 'PM2.5' || r.pollutant_id === 'PM10') s.hasPm = true;
    stations.set(r.station, s);
  }
  let list = [...stations.values()].filter(s => s.hasPm && s.aqi >= 0);
  if (!list.length) return null;
  if (Number.isFinite(lat) && Number.isFinite(lon)) {
    for (const s of list) s.km = Number.isFinite(s.lat) && Number.isFinite(s.lon) ? km(lat, lon, s.lat, s.lon) : 0;
    // A city name can be shared by places far apart; keep only monitors within reach of the user's city.
    const near = list.filter(s => s.km <= 60);
    if (!near.length) return null;
    list = near;
  }
  const worst = list.reduce((a, b) => (b.aqi > a.aqi ? b : a));
  const tally = {}; for (const s of list) tally[s.dominant] = (tally[s.dominant] || 0) + 1;
  return {
    aqi: Math.round(list.reduce((n, s) => n + s.aqi, 0) / list.length),
    stations: list.length,
    worst: { station: worst.name, aqi: Math.round(worst.aqi) },
    peak: Math.round(Math.max(...list.map(s => s.peak))),
    dominant: Object.entries(tally).sort((a, b) => b[1] - a[1])[0][0],
    updated: list[0].updated || '',
    source: 'CPCB',
  };
}

/** Cached for an hour (the monitors report hourly). If a refresh fails, the last good reading is served for up to six hours. */
async function cityAqi(city, lat, lon) {
  const k = city.toLowerCase();
  const hit = aqiCache.get(k);
  if (hit && hit.records && Date.now() - hit.at < 3_600_000) return summarise(hit.records, lat, lon);
  if (hit && hit.pending) { try { await hit.pending; } catch { /* fall through to stale */ } const h = aqiCache.get(k); return h && h.records ? summarise(h.records, lat, lon) : null; }
  const pending = cityRecords(city);
  aqiCache.set(k, { ...(hit || {}), pending });
  try {
    const records = await pending;
    aqiCache.set(k, { at: Date.now(), records });
    if (aqiCache.size > 400) aqiCache.delete(aqiCache.keys().next().value);
    return summarise(records, lat, lon);
  } catch (e) {
    aqiCache.set(k, hit ? { at: hit.at, records: hit.records } : { at: 0, records: null });
    if (hit && hit.records && Date.now() - hit.at < 6 * 3_600_000) return summarise(hit.records, lat, lon);
    throw e;
  }
}

const server = http.createServer(async (req, res) => {
  const started = Date.now();
  const url = new URL(req.url, 'http://x');
  const log = (status, note = '') => console.log(`${new Date().toISOString()} ${req.method} ${url.pathname} ${status} ${Date.now() - started}ms ${note}`);
  try {
    if (req.method === 'GET' && url.pathname === '/health') {
      res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"ok":true}'); return;
    }

    if (req.method === 'GET' && url.pathname === '/v1/models') {
      if (!checkCaller(req, Buffer.alloc(0))) { send(res, 401, 'authentication_error', 'Unrecognised client.'); return log(401); }
      if (modelsCache.body && Date.now() - modelsCache.at < 3_600_000) {
        res.writeHead(200, { 'content-type': 'application/json' }); res.end(modelsCache.body); return log(200, 'cached');
      }
      upstreamRequest('GET', '/v1/models?limit=100', {
        'x-api-key': cfg.apiKey, 'anthropic-version': '2026-10-21', 'anthropic-beta': 'server-side-fallback-2026-07-01',
      }, null, up => {
        const chunks = [];
        up.on('data', c => chunks.push(c));
        up.on('end', () => {
          if (up.statusCode !== 200) { send(res, 502, 'api_error', 'The AI service is not available right now.'); return log(502, `upstream ${up.statusCode}`); }
          try {
            const all = JSON.parse(Buffer.concat(chunks).toString('utf8'));
            const data = (all.data || []).filter(m => cfg.models.includes(m.id));
            modelsCache = { at: Date.now(), body: JSON.stringify({ data, has_more: false, first_id: data[0]?.id ?? null, last_id: data.at(-1)?.id ?? null }) };
            res.writeHead(200, { 'content-type': 'application/json' }); res.end(modelsCache.body); log(200);
          } catch { send(res, 502, 'api_error', 'The AI service is not available right now.'); log(502, 'bad models json'); }
        });
      }, () => { send(res, 502, 'api_error', 'The AI service is not available right now.'); log(502, 'upstream error'); });
      return;
    }

    if (req.method === 'GET' && url.pathname === '/v1/aqi') {
      if (!checkCaller(req, Buffer.alloc(0))) { send(res, 401, 'authentication_error', 'Unrecognised client.'); return log(401); }
      const city = String(url.searchParams.get('city') || '').trim().slice(0, 60);
      const lat = Number(url.searchParams.get('lat')), lon = Number(url.searchParams.get('lon'));
      if (!/^[\p{L} .'()-]{2,60}$/u.test(city)) { send(res, 400, 'invalid_request_error', 'Malformed request.'); return log(400, 'aqi city'); }
      if (!cfg.dataGovKey) { send(res, 503, 'api_error', 'Official air-quality data is not set up on this service.'); return log(503, 'aqi no key'); }
      try {
        const data = await cityAqi(city, lat, lon);
        if (!data) { send(res, 404, 'not_found_error', 'No official monitor is listed for this city.'); return log(404, 'aqi none'); }
        const body = JSON.stringify(data);
        res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' }); res.end(body);
        return log(200, `aqi ${data.stations} stations`);
      } catch (e) { send(res, 502, 'api_error', 'The air-quality data is not available right now.'); return log(502, 'aqi ' + (e?.message || '')); }
    }

    const syncMatch = /^\/v1\/sync\/([0-9a-f]{64})$/.exec(url.pathname);
    if (syncMatch && ['GET', 'PUT', 'DELETE'].includes(req.method)) {
      let body = Buffer.alloc(0);
      if (req.method === 'PUT') {
        try { body = await readBody(req, cfg.maxBody); }
        catch (e) { send(res, e.status || 400, e.status === 413 ? 'request_too_large' : 'invalid_request_error', 'Too much data to sync in one go.'); return log(e.status || 400, 'sync'); }
      }
      if (!checkCaller(req, body)) { send(res, 401, 'authentication_error', 'Unrecognised client.'); return log(401); }
      if (!cfg.syncDir) { send(res, 503, 'api_error', 'Sync is not set up on this service.'); return log(503, 'sync off'); }
      const file = `${cfg.syncDir}/${syncMatch[1]}.json`;
      const json = (status, obj) => { const b = JSON.stringify(obj); res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' }); res.end(b); };
      const current = () => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } };
      if (req.method === 'GET') {
        const cur = current();
        if (!cur) { send(res, 404, 'not_found_error', 'Nothing is stored for this code yet.'); return log(404, 'sync get'); }
        json(200, cur); return log(200, `sync get v${cur.version}`);
      }
      if (req.method === 'DELETE') {
        // The data goes; a small marker stays, so the person's other devices learn that sync was ended
        // on purpose and stop, instead of uploading their copy again.
        const cur = current();
        const size = (() => { try { return fs.statSync(file).size; } catch { return 0; } })();
        const marker = JSON.stringify({ version: (cur ? cur.version : 0) + 1, updated: Date.now(), erased: true });
        fs.writeFileSync(file, marker, { mode: 0o600 });
        syncTotal = Math.max(0, syncTotal - size + marker.length);
        json(200, { ok: true }); return log(200, 'sync erase');
      }
      let put;
      try { put = JSON.parse(body.toString('utf8')); } catch { send(res, 400, 'invalid_request_error', 'Malformed request.'); return log(400, 'sync'); }
      if (typeof put.data !== 'string' || !/^[A-Za-z0-9+/=]+$/.test(put.data) || !Number.isInteger(put.base)) { send(res, 400, 'invalid_request_error', 'Malformed request.'); return log(400, 'sync'); }
      roll();
      const wk = 's' + syncMatch[1].slice(0, 16);
      if ((counters.devices[wk] || 0) >= cfg.syncWritesDaily) { send(res, 429, 'rate_limit_error', 'Too many sync updates today. It resets tomorrow.', { 'retry-after': '3600' }); return log(429, 'sync writes'); }
      const cur = current();
      const version = cur ? cur.version : 0;
      // Two devices saving at once: the second is told to fetch, merge and try again, so neither overwrites the other.
      if (cur && cur.erased) { send(res, 410, 'invalid_request_error', 'Sync for this code was ended and erased.'); return log(410, 'sync erased'); }
      if (put.base !== version) { json(409, { version }); return log(409, `sync base ${put.base} != ${version}`); }
      const oldSize = (() => { try { return fs.statSync(file).size; } catch { return 0; } })();
      const next = JSON.stringify({ version: version + 1, updated: Date.now(), data: put.data });
      if (syncTotal - oldSize + next.length > cfg.syncMaxTotal) { send(res, 507, 'api_error', 'Sync storage is full for now.'); return log(507, 'sync full'); }
      const tmp = file + '.tmp';
      fs.writeFileSync(tmp, next, { mode: 0o600 }); fs.renameSync(tmp, file);
      syncTotal += next.length - oldSize;
      counters.devices[wk] = (counters.devices[wk] || 0) + 1;
      json(200, { version: version + 1 }); return log(200, `sync put v${version + 1} ${next.length}b`);
    }

    if (req.method === 'POST' && url.pathname === '/v1/messages') {
      let body;
      try { body = await readBody(req, cfg.maxBody); }
      catch (e) { send(res, e.status || 400, e.status === 413 ? 'request_too_large' : 'invalid_request_error', 'This is too long to send in one request.'); return log(e.status || 400); }
      const device = checkCaller(req, body);
      if (!device) { send(res, 401, 'authentication_error', 'Unrecognised client.'); return log(401); }

      let json;
      try { json = JSON.parse(body.toString('utf8')); } catch { send(res, 400, 'invalid_request_error', 'Malformed request.'); return log(400); }
      for (const k of Object.keys(json)) if (!ALLOWED_BODY_KEYS.has(k)) { send(res, 400, 'invalid_request_error', `"${k}" is not available through this service.`); return log(400, `key ${k}`); }
      if (!cfg.models.includes(json.model)) { send(res, 404, 'not_found_error', 'That model is not available through this service.'); return log(404, String(json.model).slice(0, 40)); }
      if (!Array.isArray(json.messages) || json.messages.length === 0 || json.messages.length > 4) { send(res, 400, 'invalid_request_error', 'Malformed request.'); return log(400); }
      if (json.system !== undefined && typeof json.system !== 'string') { send(res, 400, 'invalid_request_error', 'Malformed request.'); return log(400, 'system'); }
      const bad = checkMessages(json.messages);
      if (bad) { send(res, 400, 'invalid_request_error', bad); return log(400, 'content'); }
      // The only tool accepted is the API's own web search, capped at a few searches per answer (used by Ask AI).
      if (json.tools !== undefined) {
        const t = Array.isArray(json.tools) && json.tools.length === 1 ? json.tools[0] : null;
        if (!t || !/^web_search_\d{8}$/.test(String(t.type)) || t.name !== 'web_search') { send(res, 400, 'invalid_request_error', 'That tool is not available through this service.'); return log(400, 'tools'); }
        json.tools = [{ type: t.type, name: 'web_search', max_uses: Math.min(Number(t.max_uses) || 3, cfg.maxSearches) }];
      }
      // The only thinking setting accepted is "adaptive, with a summary" (used by Ask AI).
      if (json.thinking !== undefined) json.thinking = { type: 'adaptive', display: 'summarized' };
      json.max_tokens = Math.min(Number(json.max_tokens) || 1024, cfg.maxTokens);
      json.stream = true;

      roll();
      const dk = short(device), ak = short(addressOf(req));
      const limited =
        counters.overall >= cfg.overall ? 'The free service has reached its limit for today. It opens again tomorrow.' :
        (counters.devices[dk] || 0) >= cfg.perDevice ? 'You have reached today\'s limit on this phone. It resets tomorrow.' :
        (counters.addresses[ak] || 0) >= cfg.perAddress ? 'Too many requests from this network today. It resets tomorrow.' : null;
      if (limited) { send(res, 429, 'rate_limit_error', limited, { 'retry-after': '3600' }); return log(429, dk); }
      if ((inflight.get(dk) || 0) >= cfg.concurrent) { send(res, 429, 'rate_limit_error', 'Too many requests at once. Try again in a moment.', { 'retry-after': '3' }); return log(429, `${dk} concurrent`); }
      inflight.set(dk, (inflight.get(dk) || 0) + 1);
      let released = false;
      const release = () => { if (released) return; released = true; const n = (inflight.get(dk) || 1) - 1; if (n <= 0) inflight.delete(dk); else inflight.set(dk, n); };
      res.on('close', release);
      counters.overall++; counters.devices[dk] = (counters.devices[dk] || 0) + 1; counters.addresses[ak] = (counters.addresses[ak] || 0) + 1;

      const out = Buffer.from(JSON.stringify(json));
      const headers = {
        'x-api-key': cfg.apiKey, 'content-type': 'application/json', 'content-length': out.length,
        'anthropic-version': /^\d{4}-\d{2}-\d{2}$/.test(String(req.headers['anthropic-version'])) ? req.headers['anthropic-version'] : '2026-10-21',
      };
      const betas = String(req.headers['anthropic-beta'] || '').split(',').map(s => s.trim()).filter(b => ALLOWED_BETAS.has(b));
      if (betas.length) headers['anthropic-beta'] = betas.join(',');

      let callerGone = false;
      const up = upstreamRequest('POST', '/v1/messages', headers, out, upRes => {
        // The key's own problems are the service's problem, not something to show a user.
        if (upRes.statusCode === 401 || upRes.statusCode === 402 || upRes.statusCode === 403) {
          upRes.resume(); send(res, 502, 'api_error', 'The AI service is not available right now.'); return log(502, `upstream ${upRes.statusCode}`);
        }
        const pass = { 'content-type': upRes.headers['content-type'] || 'application/json', 'cache-control': 'no-store', 'x-accel-buffering': 'no', 'x-content-type-options': 'nosniff' };
        if (upRes.headers['retry-after']) pass['retry-after'] = upRes.headers['retry-after'];
        res.writeHead(upRes.statusCode || 502, pass);
        upRes.pipe(res);
        upRes.on('end', () => log(upRes.statusCode, `${dk} ${json.model} in=${out.length}`));
      }, () => {
        if (callerGone) return log(499, `${dk} caller closed the connection`);
        if (!res.headersSent) send(res, 502, 'api_error', 'The AI service is not available right now.'); else res.destroy();
        log(502, 'upstream error');
      });
      res.on('close', () => { if (!res.writableEnded) { callerGone = true; up.destroy(); } });
      return;
    }

    send(res, 404, 'not_found_error', 'Not found.'); log(404);
  } catch (e) {
    if (!res.headersSent) send(res, 500, 'api_error', 'Something went wrong.'); else res.destroy();
    log(500, e?.message || '');
  }
});

server.requestTimeout = 0;
server.headersTimeout = 30_000;
server.listen(cfg.port, cfg.host, () => console.log(`dcrux-proxy on ${cfg.host}:${cfg.port} · models ${cfg.models.join(', ')} · daily ${cfg.perDevice}/device ${cfg.perAddress}/address ${cfg.overall} overall · ${cfg.concurrent} at once per device · body ${cfg.maxBody} bytes`));
for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => { save(); server.close(() => process.exit(0)); setTimeout(() => process.exit(0), 3000).unref(); });
