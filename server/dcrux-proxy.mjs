// DCrux service: a small relay that keeps the Claude API key on the server.
//
// The app cannot hold a secret safely, so the public build talks to this relay instead of
// carrying a key. The relay only forwards the two calls the app makes, only for an allow-list
// of models, and counts requests per device, per address and overall so one install (or one
// person with curl) cannot run up the bill. No request or response text is logged or stored.
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
  maxBody: Number(env.DCRUX_MAX_BODY_BYTES || 3_000_000),
  perDevice: Number(env.DCRUX_DEVICE_DAILY || 60),
  perAddress: Number(env.DCRUX_ADDRESS_DAILY || 150),
  overall: Number(env.DCRUX_GLOBAL_DAILY || 2000),
  stateFile: env.DCRUX_STATE_FILE || '',
  upstream: env.DCRUX_UPSTREAM || 'https://api.anthropic.com',
};
if (!cfg.apiKey || !cfg.appSecret) {
  console.error('ANTHROPIC_API_KEY and DCRUX_APP_SECRET must be set');
  process.exit(1);
}

const ALLOWED_BODY_KEYS = new Set(['model', 'max_tokens', 'stream', 'system', 'messages', 'output_config', 'fallbacks']);
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

function send(res, status, type, message, extra = {}) {
  const body = JSON.stringify({ type: 'error', error: { type, message } });
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), ...extra });
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
  return device;
}

function addressOf(req) {
  return String(req.headers['cf-connecting-ip'] || req.headers['x-real-ip'] || req.socket.remoteAddress || 'unknown');
}

function upstreamRequest(method, path, headers, body, onResponse, onError) {
  const u = new URL(cfg.upstream + path);
  const lib = u.protocol === 'http:' ? http : https;
  const up = lib.request({ method, hostname: u.hostname, port: u.port || undefined, path: u.pathname + u.search, headers }, onResponse);
  up.on('error', onError);
  up.setTimeout(600_000, () => up.destroy(new Error('upstream timeout')));
  if (body) up.write(body);
  up.end();
  return up;
}

let modelsCache = { at: 0, body: null };

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
      json.max_tokens = Math.min(Number(json.max_tokens) || 1024, cfg.maxTokens);
      json.stream = true;

      roll();
      const dk = short(device), ak = short(addressOf(req));
      const limited =
        counters.overall >= cfg.overall ? 'The free service has reached its limit for today. It opens again tomorrow.' :
        (counters.devices[dk] || 0) >= cfg.perDevice ? 'You have reached today\'s limit on this phone. It resets tomorrow.' :
        (counters.addresses[ak] || 0) >= cfg.perAddress ? 'Too many requests from this network today. It resets tomorrow.' : null;
      if (limited) { send(res, 429, 'rate_limit_error', limited, { 'retry-after': '3600' }); return log(429, dk); }
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
        const pass = { 'content-type': upRes.headers['content-type'] || 'application/json', 'cache-control': 'no-store', 'x-accel-buffering': 'no' };
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
server.listen(cfg.port, cfg.host, () => console.log(`dcrux-proxy on ${cfg.host}:${cfg.port} · models ${cfg.models.join(', ')} · daily ${cfg.perDevice}/device ${cfg.perAddress}/address ${cfg.overall} overall`));
for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => { save(); server.close(() => process.exit(0)); setTimeout(() => process.exit(0), 3000).unref(); });
