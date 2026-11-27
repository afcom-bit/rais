import express from 'express';
import http from 'node:http';
import net from 'node:net';
import dns from 'node:dns/promises';
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import QRCode from 'qrcode';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = Number(process.env.PORT) || 3000;
const HOST = '0.0.0.0';

const STREAM_HOST = process.env.BROADCAST_HOST || 'streamy1.radioislam.my.id';
const STREAM_PORT = Number(process.env.BROADCAST_PORT) || 9210;
const BROADCAST_USER = process.env.BROADCAST_USER || 'sragen';

const ALLOWED_BITRATES = new Set([24, 32, 48, 64, 96, 128]);

const DATA_DIR = path.join(__dirname, 'data');
const AUTH_FILE = path.join(DATA_DIR, 'auth.json');

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

function defaultStationConfig() {
  return {
    host: STREAM_HOST,
    port: STREAM_PORT,
    user: BROADCAST_USER,
    pass: '',
    station: 'Radio Islam Sragen',
    genre: 'Islamic',
    bitrate: 48,
  };
}

function normalizeHostInput(raw) {
  let s = String(raw ?? '').trim().toLowerCase();
  s = s.replace(/^https?:\/\//i, '');
  s = s.split('/')[0].split('?')[0].split('#')[0];
  if (s.includes(':') && !s.startsWith('[') && net.isIP(s) === 0) {
    s = s.split(':')[0];
  }
  return s.replace(/[\r\n\0\s]+/g, '').slice(0, 253);
}

function normalizeStoredStation(raw) {
  const def = defaultStationConfig();
  if (!raw || typeof raw !== 'object') return def;
  const portNum = Number(raw.port);
  const brNum = Number(raw.bitrate);
  const rawUser = raw.user === undefined || raw.user === null ? def.user : String(raw.user).replace(/[\r\n\0]+/g, ' ').trim().slice(0, 64);
  return {
    host: normalizeHostInput(raw.host) || def.host,
    port: Number.isInteger(portNum) && portNum >= 1 && portNum <= 65535 ? portNum : def.port,
    user: rawUser,
    pass: typeof raw.pass === 'string' ? raw.pass.replace(/[\r\n\0]+/g, '').slice(0, 128) : '',
    station: cleanHeaderValue(raw.station, def.station, 100),
    genre: cleanHeaderValue(raw.genre, def.genre, 48),
    bitrate: ALLOWED_BITRATES.has(brNum) ? brNum : def.bitrate,
  };
}

function ensureAuthFile() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
  }
  if (!fs.existsSync(AUTH_FILE)) {
    const initial = { accounts: [], station: defaultStationConfig() };
    fs.writeFileSync(AUTH_FILE, JSON.stringify(initial, null, 2), { mode: 0o600 });
    return initial;
  }
  try {
    const raw = fs.readFileSync(AUTH_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    const list = Array.isArray(parsed.accounts) ? parsed.accounts : [];
    const station = normalizeStoredStation(parsed.station);
    return { accounts: list, station };
  } catch {
    return { accounts: [], station: defaultStationConfig() };
  }
}

let authState = ensureAuthFile();

function saveAuthState() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
  }
  const tmpFile = path.join(DATA_DIR, `.auth.${crypto.randomBytes(6).toString('hex')}.tmp`);
  fs.writeFileSync(
    tmpFile,
    JSON.stringify({ accounts: authState.accounts, station: authState.station }, null, 2),
    { mode: 0o600 }
  );
  fs.renameSync(tmpFile, AUTH_FILE);
}

function base32Encode(buf) {
  let bits = 0;
  let value = 0;
  let output = '';
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) {
    output += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  }
  return output;
}

function base32Decode(str) {
  const clean = String(str || '').toUpperCase().replace(/[^A-Z2-7]/g, '');
  let bits = 0;
  let value = 0;
  const out = [];
  for (const ch of clean) {
    const idx = BASE32_ALPHABET.indexOf(ch);
    if (idx === -1) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

function generateTotpSecret() {
  return base32Encode(crypto.randomBytes(20));
}

function hotp(secretBase32, counter) {
  const key = base32Decode(secretBase32);
  const buf = Buffer.alloc(8);
  let val = BigInt(counter);
  for (let i = 7; i >= 0; i--) {
    buf[i] = Number(val & 0xffn);
    val >>= 8n;
  }
  const hmac = crypto.createHmac('sha1', key).update(buf).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const code =
    ((hmac[offset] & 0x7f) << 24) |
    ((hmac[offset + 1] & 0xff) << 16) |
    ((hmac[offset + 2] & 0xff) << 8) |
    (hmac[offset + 3] & 0xff);
  return String(code % 1000000).padStart(6, '0');
}

function verifyTotpCode(secretBase32, rawCode, lastUsedStep) {
  const code = String(rawCode ?? '').replace(/\s+/g, '');
  if (!/^\d{6}$/.test(code)) return { ok: false };
  const currentStep = Math.floor(Date.now() / 30000);
  const candidateBuf = Buffer.from(code, 'utf8');
  for (const offset of [-1, 0, 1]) {
    const step = currentStep + offset;
    if (step < 0) continue;
    if (Number.isInteger(lastUsedStep) && step <= lastUsedStep) continue;
    const expectedBuf = Buffer.from(hotp(secretBase32, step), 'utf8');
    if (expectedBuf.length === candidateBuf.length && crypto.timingSafeEqual(expectedBuf, candidateBuf)) {
      return { ok: true, step };
    }
  }
  return { ok: false };
}

function buildOtpAuthUri(email, secret) {
  const label = encodeURIComponent(`Radio Islam Sragen:${email}`);
  const issuer = encodeURIComponent('Radio Islam Sragen');
  return `otpauth://totp/${label}?secret=${secret}&issuer=${issuer}&algorithm=SHA1&digits=6&period=30`;
}

function normalizeEmail(raw) {
  return String(raw ?? '').trim().toLowerCase();
}

function isValidEmail(email) {
  return email.length <= 160 && /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/.test(email);
}

function sanitizeAccount(acc) {
  return {
    id: acc.id,
    email: acc.email,
    name: acc.name || '',
    enabled: Boolean(acc.enabled),
    createdAt: acc.createdAt,
    lastLoginAt: acc.lastLoginAt || null,
  };
}

let activeBroadcast = null;

const PROXY_ROUTES = {
  '/stream': { targetPath: '/;', isStream: true },
  '/info/stats': { targetPath: '/stats?sid=1&json=1', isStream: false },
  '/info/icecast': { targetPath: '/status-json.xsl', isStream: false },
  '/info/song': { targetPath: '/currentsong?sid=1', isStream: false },
  '/info/next': { targetPath: '/nextsong?sid=1', isStream: false },
  '/info/7': { targetPath: '/7.html', isStream: false },
};

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1);
app.use((req, res, next) => {
  res.setHeader('Permissions-Policy', 'microphone=(self)');
  next();
});
app.use(express.json({ limit: '24kb' }));

function proxyUpstream(req, res, { targetPath, isStream }) {
  res.setHeader('Cache-Control', 'no-store');

  const upstreamReq = http.request(
    {
      hostname: STREAM_HOST,
      port: STREAM_PORT,
      path: targetPath,
      method: 'GET',
      headers: {
        'User-Agent': req.get('user-agent') || 'RAIS-Player/1.0',
        Accept: '*/*',
      },
    },
    (upstreamRes) => {
      const headers = {};
      if (upstreamRes.headers['content-type']) {
        headers['Content-Type'] = upstreamRes.headers['content-type'];
      }
      if (isStream && upstreamRes.headers['icy-name']) {
        headers['icy-name'] = upstreamRes.headers['icy-name'];
      }
      headers['Cache-Control'] = 'no-store';

      res.writeHead(upstreamRes.statusCode || 200, headers);
      upstreamRes.pipe(res);

      upstreamRes.on('error', () => {
        if (!res.writableEnded) res.end();
      });
    }
  );

  if (!isStream) {
    upstreamReq.setTimeout(8000, () => {
      upstreamReq.destroy(new Error('Upstream timeout'));
    });
  }

  upstreamReq.on('error', () => {
    if (!res.headersSent) {
      res.status(502).json({ error: 'Upstream radio server unavailable' });
    } else if (!res.writableEnded) {
      res.end();
    }
  });

  req.on('close', () => {
    upstreamReq.destroy();
  });

  upstreamReq.end();
}

for (const [route, target] of Object.entries(PROXY_ROUTES)) {
  app.get(route, (req, res) => proxyUpstream(req, res, target));
}

function cleanHeaderValue(val, fallback = '', maxLen = 120) {
  const s = String(val ?? '')
    .replace(/[\r\n\0]+/g, ' ')
    .trim()
    .slice(0, maxLen);
  return s || fallback;
}

function isDisallowedIp(ip) {
  const ver = net.isIP(ip);
  if (!ver) return true;
  if (ver === 4) {
    const p = ip.split('.').map(Number);
    if (p.length !== 4 || p.some((n) => Number.isNaN(n) || n < 0 || n > 255)) return true;
    if (p[0] === 0) return true;
    if (p[0] === 10) return true;
    if (p[0] === 100 && p[1] >= 64 && p[1] <= 127) return true;
    if (p[0] === 127) return true;
    if (p[0] === 169 && p[1] === 254) return true;
    if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return true;
    if (p[0] === 192 && p[1] === 168) return true;
    if (p[0] >= 224) return true;
    return false;
  }
  const lower = ip.toLowerCase();
  if (lower === '::' || lower === '::1') return true;
  if (lower.startsWith('::ffff:')) {
    return isDisallowedIp(lower.slice(7));
  }
  if (lower.startsWith('fe80:') || lower.startsWith('fc') || lower.startsWith('fd') || lower.startsWith('ff')) {
    return true;
  }
  return false;
}

async function resolvePublicHost(rawHost) {
  const host = String(rawHost ?? '').trim().toLowerCase();
  if (!host || host.length > 253) {
    throw new Error('Alamat server siaran tidak valid.');
  }
  if (host === 'localhost' || host.endsWith('.local') || host.endsWith('.internal')) {
    throw new Error('Alamat server lokal tidak diizinkan.');
  }
  if (!/^[a-z0-9.-]+$/.test(host)) {
    throw new Error('Format alamat server tidak valid.');
  }
  if (net.isIP(host)) {
    if (isDisallowedIp(host)) {
      throw new Error('Alamat IP tujuan tidak diizinkan.');
    }
    return { host, ip: host, family: net.isIP(host) };
  }
  const records = await dns.lookup(host, { all: true });
  if (!records || records.length === 0) {
    throw new Error('Alamat server tidak ditemukan.');
  }
  const publicRecord = records.find((r) => !isDisallowedIp(r.address));
  if (!publicRecord) {
    throw new Error('Alamat tujuan mengarah ke jaringan privat.');
  }
  return { host, ip: publicRecord.address, family: publicRecord.family };
}

/* ==================== API PENGATURAN & AKUN PENYIAR (TANPA KODE) ==================== */

app.get('/api/admin/status', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.json({
    needsSetup: false,
    authenticated: true,
    openAccess: true,
  });
});

app.get('/api/admin/accounts', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.json({
    accounts: authState.accounts.map(sanitizeAccount),
  });
});

app.post('/api/admin/accounts', async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const email = normalizeEmail(req.body?.email);
  const name = cleanHeaderValue(req.body?.name, '', 60);

  if (!isValidEmail(email)) {
    res.status(400).json({ error: 'Masukkan alamat email Gmail yang valid.' });
    return;
  }
  if (authState.accounts.some((a) => a.email === email)) {
    res.status(409).json({ error: 'Akun email ini sudah terdaftar.' });
    return;
  }

  const totpSecret = generateTotpSecret();
  const account = {
    id: crypto.randomUUID(),
    email,
    name,
    totpSecret,
    enabled: true,
    lastUsedStep: null,
    createdAt: new Date().toISOString(),
    lastLoginAt: null,
  };
  authState.accounts.push(account);
  saveAuthState();

  const otpauthUri = buildOtpAuthUri(email, totpSecret);
  const qrDataUrl = await QRCode.toDataURL(otpauthUri, {
    width: 220,
    margin: 1,
    color: { dark: '#12324a', light: '#ffffff' },
  });

  res.status(201).json({
    account: sanitizeAccount(account),
    totpSecret,
    otpauthUri,
    qrDataUrl,
  });
});

app.patch('/api/admin/accounts/:id', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const id = String(req.params.id || '');
  const acc = authState.accounts.find((a) => a.id === id);
  if (!acc) {
    res.status(404).json({ error: 'Akun tidak ditemukan.' });
    return;
  }

  if (typeof req.body?.enabled === 'boolean') {
    acc.enabled = req.body.enabled;
  }
  if (typeof req.body?.name === 'string') {
    acc.name = cleanHeaderValue(req.body.name, '', 60);
  }
  saveAuthState();
  res.json({ account: sanitizeAccount(acc) });
});

app.post('/api/admin/accounts/:id/reset-totp', async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const id = String(req.params.id || '');
  const acc = authState.accounts.find((a) => a.id === id);
  if (!acc) {
    res.status(404).json({ error: 'Akun tidak ditemukan.' });
    return;
  }
  acc.totpSecret = generateTotpSecret();
  acc.lastUsedStep = null;
  saveAuthState();

  const otpauthUri = buildOtpAuthUri(acc.email, acc.totpSecret);
  const qrDataUrl = await QRCode.toDataURL(otpauthUri, {
    width: 220,
    margin: 1,
    color: { dark: '#12324a', light: '#ffffff' },
  });

  res.json({
    account: sanitizeAccount(acc),
    totpSecret: acc.totpSecret,
    otpauthUri,
    qrDataUrl,
  });
});

app.delete('/api/admin/accounts/:id', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const id = String(req.params.id || '');
  const idx = authState.accounts.findIndex((a) => a.id === id);
  if (idx === -1) {
    res.status(404).json({ error: 'Akun tidak ditemukan.' });
    return;
  }
  authState.accounts.splice(idx, 1);
  saveAuthState();
  res.json({ ok: true });
});

function getEffectiveStationConfig() {
  const s = authState.station || defaultStationConfig();
  const savedPass = typeof s.pass === 'string' ? s.pass : '';
  const envPass = typeof process.env.BROADCAST_PASS === 'string' ? process.env.BROADCAST_PASS : '';
  const hasExplicitUser = typeof s.user === 'string';
  return {
    host: s.host || STREAM_HOST,
    port: Number.isInteger(s.port) && s.port >= 1 && s.port <= 65535 ? s.port : STREAM_PORT,
    user: hasExplicitUser ? s.user : BROADCAST_USER,
    pass: savedPass || envPass || '',
    hasSavedPass: savedPass.length > 0,
    hasEnvPass: envPass.length > 0,
    station: s.station || 'Radio Islam Sragen',
    genre: s.genre || 'Islamic',
    bitrate: ALLOWED_BITRATES.has(Number(s.bitrate)) ? Number(s.bitrate) : 48,
  };
}

function publicStationPayload() {
  const cfg = getEffectiveStationConfig();
  return {
    host: cfg.host,
    port: cfg.port,
    user: cfg.user,
    station: cfg.station,
    genre: cfg.genre,
    bitrate: cfg.bitrate,
    hasPass: Boolean(cfg.pass),
    hasSavedPass: cfg.hasSavedPass,
    hasEnvPass: cfg.hasEnvPass,
  };
}

async function validateStationHostForSave(rawHost) {
  const host = normalizeHostInput(rawHost);
  if (!host || host.length > 253) {
    throw new Error('Alamat server siaran tidak valid.');
  }
  if (host === 'localhost' || host.endsWith('.local') || host.endsWith('.internal')) {
    throw new Error('Alamat server lokal tidak diizinkan.');
  }
  if (!/^[a-z0-9.-]+$/.test(host)) {
    throw new Error('Format alamat server tidak valid.');
  }
  if (net.isIP(host)) {
    if (isDisallowedIp(host)) {
      throw new Error('Alamat IP tujuan tidak diizinkan.');
    }
    return host;
  }
  try {
    const records = await dns.lookup(host, { all: true });
    if (records && records.length > 0 && !records.some((r) => !isDisallowedIp(r.address))) {
      throw new Error('Alamat tujuan mengarah ke jaringan privat.');
    }
  } catch (err) {
    if (err && err.message && err.message.includes('jaringan privat')) {
      throw err;
    }
  }
  return host;
}

app.get('/api/admin/station', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.json({ station: publicStationPayload() });
});

async function handleSaveStation(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  const body = req.body && typeof req.body === 'object' ? req.body : {};

  let host;
  try {
    host = await validateStationHostForSave(body.host);
  } catch (err) {
    res.status(400).json({ error: err.message || 'Alamat server tidak valid.' });
    return;
  }

  const port = Number(body.port);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    res.status(400).json({ error: 'Nomor port harus antara 1 sampai 65535.' });
    return;
  }

  const user = cleanHeaderValue(body.user, '', 64);

  const bitrate = Number(body.bitrate);
  if (!ALLOWED_BITRATES.has(bitrate)) {
    res.status(400).json({ error: 'Pilihan bitrate tidak valid.' });
    return;
  }

  const stationName = cleanHeaderValue(body.station, 'Radio Islam Sragen', 100);
  const genre = cleanHeaderValue(body.genre, authState.station?.genre || 'Islamic', 48);

  let nextPass = authState.station?.pass || '';
  if (body.clearPass === true) {
    nextPass = '';
  } else if (typeof body.pass === 'string' && body.pass.length > 0) {
    nextPass = body.pass.replace(/[\r\n\0]+/g, '').slice(0, 128);
  }

  authState.station = {
    host,
    port,
    user,
    pass: nextPass,
    station: stationName,
    genre,
    bitrate,
  };
  saveAuthState();
  res.json({ ok: true, station: publicStationPayload() });
}

app.put('/api/admin/station', handleSaveStation);
app.post('/api/admin/station', handleSaveStation);

/* ==================== API KONFIGURASI SIARAN (TANPA KODE) ==================== */

app.get('/api/broadcast/session', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.json({
    authenticated: true,
    openAccess: true,
  });
});

app.get('/api/broadcast/config', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.json({
    ...publicStationPayload(),
    busy: Boolean(activeBroadcast),
  });
});

function fetchHttpJson({ ip, host, port, reqPath }) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: ip,
        port,
        path: reqPath,
        method: 'GET',
        headers: {
          Host: `${host}:${port}`,
          'User-Agent': 'RAIS-Caster/1.0',
          Accept: 'application/json,*/*',
        },
        timeout: 3500,
      },
      (res) => {
        let body = '';
        res.on('data', (chunk) => {
          if (body.length < 65536) body += chunk.toString('utf8');
        });
        res.on('end', () => {
          if (res.statusCode < 200 || res.statusCode >= 300) {
            reject(new Error(`HTTP ${res.statusCode}`));
            return;
          }
          try {
            resolve(JSON.parse(body));
          } catch {
            reject(new Error('Invalid JSON'));
          }
        });
      }
    );
    req.on('timeout', () => req.destroy(new Error('Timeout')));
    req.on('error', (err) => reject(err));
    req.end();
  });
}

app.get('/api/broadcast/status', async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const cfg = getEffectiveStationConfig();
  const byThisApp = Boolean(activeBroadcast);

  let statsTargetHost = cfg.host || STREAM_HOST;
  let statsPorts = [];
  if (statsTargetHost === STREAM_HOST) {
    statsPorts = [STREAM_PORT];
    if (cfg.port && cfg.port !== STREAM_PORT) statsPorts.unshift(cfg.port);
  } else {
    statsPorts = [cfg.port || STREAM_PORT];
  }

  let resolved;
  try {
    resolved = await resolvePublicHost(statsTargetHost);
  } catch {
    res.json({
      ok: false,
      online: byThisApp,
      sourceType: byThisApp ? 'live' : 'unknown',
      sourceLabel: byThisApp ? 'Siaran Langsung (Studio Ini)' : 'Status server tidak diketahui',
      byThisApp,
      songTitle: '',
      nextTitle: '',
      bitrate: byThisApp ? cfg.bitrate : null,
      listeners: null,
    });
    return;
  }

  let scStats = null;
  let iceStats = null;
  for (const p of [...new Set(statsPorts)]) {
    if (!Number.isInteger(p) || p < 1 || p > 65535) continue;
    try {
      const data = await fetchHttpJson({
        ip: resolved.ip,
        host: resolved.host,
        port: p,
        reqPath: '/stats?sid=1&json=1',
      });
      if (data && typeof data === 'object' && 'streamstatus' in data) {
        scStats = data;
        break;
      }
    } catch {}
    try {
      const ice = await fetchHttpJson({
        ip: resolved.ip,
        host: resolved.host,
        port: p,
        reqPath: '/status-json.xsl',
      });
      if (ice && ice.icestats) {
        iceStats = ice.icestats;
        break;
      }
    } catch {}
  }

  if (scStats) {
    const online = Number(scStats.streamstatus) === 1 || byThisApp;
    const songTitle = String(scStats.songtitle || '').trim();
    const nextTitle = String(scStats.nexttitle || '').trim();
    const serverUrl = String(scStats.serverurl || '').trim().toLowerCase();
    const bitrate = Number(scStats.bitrate) > 0 ? Math.round(Number(scStats.bitrate)) : null;
    const listeners = Number.isFinite(Number(scStats.currentlisteners)) ? Number(scStats.currentlisteners) : null;

    let sourceType = 'offline';
    let sourceLabel = 'Off Air';
    if (byThisApp) {
      sourceType = 'live';
      sourceLabel = 'Siaran Langsung (Studio Ini)';
    } else if (online) {
      if (nextTitle.length > 0 || serverUrl.includes('streamy.local') || serverUrl.includes('autodj')) {
        sourceType = 'autodj';
        sourceLabel = 'AutoDJ';
      } else {
        sourceType = 'live';
        sourceLabel = 'Siaran Langsung';
      }
    }

    res.json({
      ok: true,
      online,
      sourceType,
      sourceLabel,
      byThisApp,
      songTitle,
      nextTitle,
      serverUrl,
      bitrate,
      listeners,
      version: String(scStats.version || ''),
    });
    return;
  }

  if (iceStats) {
    let src = iceStats.source;
    if (Array.isArray(src)) src = src[0];
    const online = Boolean(src) || byThisApp;
    const songTitle = src ? String(src.title ? (src.artist ? `${src.artist} - ${src.title}` : src.title) : (src.yp_currently_playing || '')).trim() : '';
    const listeners = src && Number.isFinite(Number(src.listeners)) ? Number(src.listeners) : null;
    const bitrate = src && Number(src.bitrate ?? src['ice-bitrate']) > 0 ? Math.round(Number(src.bitrate ?? src['ice-bitrate'])) : null;
    res.json({
      ok: true,
      online,
      sourceType: byThisApp ? 'live' : (online ? 'live' : 'offline'),
      sourceLabel: byThisApp ? 'Siaran Langsung (Studio Ini)' : (online ? 'Siaran Langsung' : 'Off Air'),
      byThisApp,
      songTitle,
      nextTitle: '',
      bitrate,
      listeners,
      version: 'Icecast',
    });
    return;
  }

  res.json({
    ok: false,
    online: byThisApp,
    sourceType: byThisApp ? 'live' : 'unknown',
    sourceLabel: byThisApp ? 'Siaran Langsung (Studio Ini)' : 'Status sumber tidak terbaca',
    byThisApp,
    songTitle: '',
    nextTitle: '',
    bitrate: byThisApp ? cfg.bitrate : null,
    listeners: null,
  });
});

const BLOCKED_STATIC_PATHS = new Set([
  '/server.js',
  '/package.json',
  '/bun.lock',
  '/vercel.json',
  '/.vercelignore',
  '/.env',
  '/.env.example',
]);

app.use((req, res, next) => {
  const reqPath = (req.path || '').toLowerCase();
  if (
    reqPath === '/data' ||
    reqPath.startsWith('/data/') ||
    reqPath.startsWith('/.') ||
    BLOCKED_STATIC_PATHS.has(reqPath)
  ) {
    res.status(404).end();
    return;
  }
  next();
});

app.use(express.static(__dirname, { extensions: ['html'], dotfiles: 'deny' }));

const server = http.createServer(app);
const wss = new WebSocketServer({
  noServer: true,
  maxPayload: 256 * 1024,
});

function isOriginAllowed(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  const forwardedHost = req.headers['x-forwarded-host'];
  const hostHeader = Array.isArray(forwardedHost) ? forwardedHost[0] : (forwardedHost || req.headers.host);
  if (!hostHeader) return false;
  try {
    const parsed = new URL(origin);
    const expectedHost = String(hostHeader).split(',')[0].trim().toLowerCase();
    if (parsed.host.toLowerCase() === expectedHost) return true;
    const rawHost = String(req.headers.host || '').split(',')[0].trim().toLowerCase();
    if (!forwardedHost && (rawHost.startsWith('127.0.0.1') || rawHost.startsWith('localhost') || rawHost.startsWith('[::1]'))) {
      return parsed.protocol === 'https:' || parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1';
    }
    return false;
  } catch {
    return false;
  }
}

server.on('upgrade', (req, socket, head) => {
  const reqPath = (req.url || '').split('?')[0];
  if (reqPath !== '/ws/broadcast') {
    socket.destroy();
    return;
  }
  if (!isOriginAllowed(req)) {
    socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req);
  });
});

function makeConnError(message, kind = 'protocol') {
  const err = new Error(message);
  err.kind = kind;
  return err;
}

function tryShoutcastSocket({ ip, family, targetPort, authLine, station, genre, bitrate }) {
  return new Promise((resolve, reject) => {
    if (!Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) {
      reject(makeConnError('Port sumber Shoutcast di luar batas.', 'protocol'));
      return;
    }
    const sock = net.connect({ host: ip, port: targetPort, family });
    let settled = false;
    let respBuf = '';

    const fail = (err) => {
      if (settled) return;
      settled = true;
      sock.destroy();
      reject(err);
    };

    sock.setTimeout(6500, () => {
      fail(makeConnError(`Waktu sambung ke port ${targetPort} habis.`, 'network'));
    });

    sock.on('error', (err) => {
      const e = makeConnError(err.message || 'Gagal menyambung port siaran.', 'network');
      fail(e);
    });

    sock.on('connect', () => {
      sock.write(`${authLine}\r\n`);
    });

    sock.on('data', (chunk) => {
      if (settled) return;
      respBuf += chunk.toString('utf8');
      if (respBuf.length > 2048) {
        fail(makeConnError('Respon server siaran tidak dikenal.', 'protocol'));
        return;
      }
      if (!respBuf.includes('\n')) return;

      const trimmed = respBuf.replace(/^[\r\n\s]+/, '');
      const lower = trimmed.toLowerCase();
      if (lower.startsWith('http/')) {
        fail(makeConnError('Port tersebut menerima HTTP, bukan protokol sumber Shoutcast.', 'http'));
        return;
      }
      if (lower.includes('invalid password') || lower.includes('invalid')) {
        fail(makeConnError('User atau kata sandi siaran salah.', 'auth'));
        return;
      }
      if (lower.includes('already in use')) {
        fail(makeConnError('Slot siaran pada port tersebut sedang digunakan.', 'busy'));
        return;
      }
      if (trimmed.startsWith('OK2') || trimmed.startsWith('OK')) {
        settled = true;
        sock.setTimeout(0);
        sock.removeAllListeners('data');
        const headers = [
          'content-type:audio/mpeg',
          `icy-name:${station}`,
          `icy-genre:${genre}`,
          'icy-url:https://radioislam.my.id',
          'icy-pub:1',
          `icy-br:${bitrate}`,
          'icy-reset:1',
          '',
          '',
        ].join('\r\n');
        sock.write(headers);
        resolve({ socket: sock, mode: 'shoutcast', connectedPort: targetPort });
        return;
      }
      fail(makeConnError(`Server menolak sambungan: ${trimmed.trim()}`, 'protocol'));
    });

    sock.on('close', () => {
      if (!settled) fail(makeConnError(`Sambungan port ${targetPort} ditutup oleh server.`, 'protocol'));
    });
  });
}

function tryIcecastSocket({ ip, family, host, targetPort, user, pass, mount, station, genre, bitrate }) {
  return new Promise((resolve, reject) => {
    const cleanMount = '/' + String(mount || 'stream').replace(/^\/+/, '').replace(/[\r\n\0\s]+/g, '');
    const sock = net.connect({ host: ip, port: targetPort, family });
    let settled = false;
    let respBuf = '';

    const fail = (err) => {
      if (settled) return;
      settled = true;
      sock.destroy();
      reject(err);
    };

    sock.setTimeout(6500, () => fail(makeConnError('Waktu sambung Icecast habis.', 'network')));
    sock.on('error', (err) => fail(makeConnError(err.message || 'Gagal menyambung Icecast.', 'network')));

    sock.on('connect', () => {
      const auth = Buffer.from(`${user || 'source'}:${pass}`).toString('base64');
      const reqHeaders = [
        `SOURCE ${cleanMount} HTTP/1.0`,
        `Host: ${host}:${targetPort}`,
        `Authorization: Basic ${auth}`,
        'User-Agent: RAIS-Caster/1.0',
        'Content-Type: audio/mpeg',
        `ice-name: ${station}`,
        `ice-genre: ${genre}`,
        'ice-public: 1',
        `ice-bitrate: ${bitrate}`,
        '',
        '',
      ].join('\r\n');
      sock.write(reqHeaders);
    });

    sock.on('data', (chunk) => {
      if (settled) return;
      respBuf += chunk.toString('utf8');
      if (respBuf.length > 4096) {
        fail(makeConnError('Respon Icecast tidak dikenal.', 'protocol'));
        return;
      }
      if (!respBuf.includes('\r\n\r\n') && !respBuf.includes('\n\n') && !respBuf.includes('\n')) return;
      const firstLine = respBuf.split(/\r?\n/)[0] || '';
      if (/^HTTP\/1\.[01]\s+200\b/i.test(firstLine)) {
        settled = true;
        sock.setTimeout(0);
        sock.removeAllListeners('data');
        resolve({ socket: sock, mode: 'icecast', mount: cleanMount, connectedPort: targetPort });
        return;
      }
      if (/401|403/.test(firstLine)) {
        fail(makeConnError('User atau kata sandi Icecast salah.', 'auth'));
        return;
      }
      if (/404/.test(firstLine)) {
        fail(makeConnError('Mountpoint tidak ditemukan.', 'notfound'));
        return;
      }
      fail(makeConnError(`Server menolak: ${firstLine || respBuf.trim()}`, 'protocol'));
    });

    sock.on('close', () => {
      if (!settled) fail(makeConnError('Sambungan Icecast ditutup.', 'protocol'));
    });
  });
}

async function connectBroadcastUpstream({ ip, family, host, port, user, pass, mount, station, genre, bitrate }) {
  const cleanUser = String(user || '').trim();
  const authCandidates = [];
  if (cleanUser) authCandidates.push(`${cleanUser}:${pass}`);
  authCandidates.push(pass);
  authCandidates.push(`1:${pass}`);
  const uniqueAuthLines = [...new Set(authCandidates)];

  let sawBusy = false;
  let sawAuthFail = false;
  let lastNetworkErr = null;

  // 1. Shoutcast v1/v2 standar & Liquidsoap Harbor ICY pada port + 1
  if (port + 1 <= 65535) {
    for (const authLine of uniqueAuthLines) {
      try {
        return await tryShoutcastSocket({
          ip,
          family,
          targetPort: port + 1,
          authLine,
          station,
          genre,
          bitrate,
        });
      } catch (err) {
        if (err.kind === 'busy') {
          sawBusy = true;
          break;
        }
        if (err.kind === 'auth') {
          sawAuthFail = true;
          continue;
        }
        if (err.kind === 'network') {
          lastNetworkErr = err;
          break;
        }
        break;
      }
    }
  }

  // 2. Icecast / Liquidsoap Harbor HTTP SOURCE pada port langsung
  const iceUsers = [...new Set([cleanUser || 'source', 'source'])];
  const iceMounts = [...new Set([mount || '/stream', '/'])];
  let portSpokeHttp = false;

  for (const iceUser of iceUsers) {
    let abortIceUser = false;
    for (const iceMount of iceMounts) {
      try {
        return await tryIcecastSocket({
          ip,
          family,
          host,
          targetPort: port,
          user: iceUser,
          pass,
          mount: iceMount,
          station,
          genre,
          bitrate,
        });
      } catch (err) {
        if (err.kind === 'auth') {
          portSpokeHttp = true;
          sawAuthFail = true;
          break;
        }
        if (err.kind === 'notfound') {
          portSpokeHttp = true;
          continue;
        }
        if (err.kind === 'network') {
          lastNetworkErr = err;
          abortIceUser = true;
          break;
        }
        abortIceUser = true;
        break;
      }
    }
    if (abortIceUser) break;
  }

  // 3. Jika user memasukkan port Shoutcast / DJ port secara langsung pada kolom Port
  if (!portSpokeHttp) {
    for (const authLine of uniqueAuthLines) {
      try {
        return await tryShoutcastSocket({
          ip,
          family,
          targetPort: port,
          authLine,
          station,
          genre,
          bitrate,
        });
      } catch (err) {
        if (err.kind === 'busy') {
          sawBusy = true;
          break;
        }
        if (err.kind === 'auth') {
          sawAuthFail = true;
          continue;
        }
        if (err.kind === 'network') {
          lastNetworkErr = err;
          break;
        }
        break;
      }
    }
  }

  if (sawBusy) {
    throw new Error(
      'Slot siaran utama sedang digunakan (kemungkinan AutoDJ aktif). Jika server memakai Port DJ khusus (mis. Streamy Panel user "dj"), isi Port DJ tersebut di Pengaturan.'
    );
  }
  if (sawAuthFail) {
    throw new Error('User atau kata sandi stream salah. Periksa Port, User, dan Kata Sandi di Pengaturan.');
  }
  if (lastNetworkErr) {
    throw new Error(`Tidak dapat menjangkau server siaran pada port ${port} atau ${port + 1}. Periksa alamat dan port.`);
  }
  throw new Error('Gagal tersambung ke server siaran. Periksa pengaturan stasiun.');
}

function updateUpstreamMetadata({ ip, host, port, user, pass, title, mode, mount }) {
  return new Promise((resolve, reject) => {
    const song = cleanHeaderValue(title, '', 180);
    if (!song) {
      resolve(false);
      return;
    }
    const auth = Buffer.from(`${user || 'admin'}:${pass}`).toString('base64');
    const reqPath =
      mode === 'icecast'
        ? `/admin/metadata?mount=${encodeURIComponent(mount || '/stream')}&mode=updinfo&song=${encodeURIComponent(song)}`
        : `/admin.cgi?pass=${encodeURIComponent(pass)}&mode=updinfo&song=${encodeURIComponent(song)}&sid=1`;

    const req = http.request(
      {
        host: ip,
        port,
        path: reqPath,
        method: 'GET',
        headers: {
          Host: `${host}:${port}`,
          Authorization: `Basic ${auth}`,
          'User-Agent': 'Mozilla/5.0 RAIS-Caster/1.0',
        },
        timeout: 5000,
      },
      (res) => {
        res.resume();
        resolve(res.statusCode >= 200 && res.statusCode < 400);
      }
    );
    req.on('timeout', () => {
      req.destroy(new Error('Timeout'));
    });
    req.on('error', (err) => reject(err));
    req.end();
  });
}

wss.on('connection', (ws) => {
  const sendJson = (payload) => {
    if (ws.readyState === 1) {
      ws.send(JSON.stringify(payload));
    }
  };

  sendJson({ type: 'ready', busy: Boolean(activeBroadcast) });

  let session = null;

  const cleanupSession = (reasonMessage) => {
    if (!session) return;
    const s = session;
    session = null;
    if (activeBroadcast === s) activeBroadcast = null;
    clearInterval(s.statsTimer);
    try {
      if (s.ff && !s.ff.killed) {
        if (s.ff.stdin && !s.ff.stdin.destroyed) s.ff.stdin.end();
        s.ff.kill('SIGTERM');
      }
    } catch {}
    try {
      if (s.upstream && !s.upstream.destroyed) s.upstream.destroy();
    } catch {}
    if (reasonMessage) {
      sendJson({ type: 'stopped', message: reasonMessage });
    }
  };

  ws.on('message', async (data, isBinary) => {
    if (isBinary) {
      if (session && session.ff && session.ff.stdin && session.ff.stdin.writable && !session.ff.stdin.destroyed) {
        session.ff.stdin.write(Buffer.from(data));
      }
      return;
    }

    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch {
      return;
    }

    if (!msg || typeof msg !== 'object') return;

    if (msg.type === 'stop') {
      cleanupSession('Siaran dihentikan.');
      return;
    }

    if (msg.type === 'metadata') {
      if (!session) return;
      const nextTitle = cleanHeaderValue(msg.title, '', 180);
      if (!nextTitle) return;
      try {
        const ok = await updateUpstreamMetadata({
          ip: session.ip,
          host: session.host,
          port: session.httpPort,
          user: session.user,
          pass: session.pass,
          title: nextTitle,
          mode: session.mode,
          mount: session.mount,
        });
        sendJson({ type: 'meta_ok', title: nextTitle, ok });
      } catch {
        sendJson({ type: 'meta_ok', title: nextTitle, ok: false });
      }
      return;
    }

    if (msg.type === 'start') {
      if (session) {
        sendJson({ type: 'error', message: 'Sesi siaran sudah berjalan.' });
        return;
      }

      if (activeBroadcast) {
        sendJson({ type: 'error', message: 'Sedang ada penyiar lain yang aktif di server.' });
        return;
      }

      const cfg = getEffectiveStationConfig();
      const rawHost = normalizeHostInput(msg.host) || cfg.host;
      const hasPort = msg.port !== undefined && msg.port !== null && msg.port !== '';
      const port = hasPort ? Number(msg.port) : cfg.port;
      const user = msg.user !== undefined ? cleanHeaderValue(msg.user, '', 64) : cfg.user;
      const pass =
        typeof msg.pass === 'string' && msg.pass.length > 0
          ? msg.pass.replace(/[\r\n\0]+/g, '').slice(0, 128)
          : cfg.pass;
      const bitrate = ALLOWED_BITRATES.has(Number(msg.bitrate)) ? Number(msg.bitrate) : cfg.bitrate;
      const rawRate = Math.round(Number(msg.sampleRate));
      const sampleRate = Number.isInteger(rawRate) && rawRate >= 8000 && rawRate <= 96000 ? rawRate : 44100;
      const outSampleRate = bitrate <= 24 ? 22050 : 44100;
      const station = cleanHeaderValue(msg.station, cfg.station, 100);
      const genre = cleanHeaderValue(msg.genre, cfg.genre, 48);
      const title = cleanHeaderValue(msg.title, '', 180);
      const mount = cleanHeaderValue(msg.mount, '/stream', 64);

      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        sendJson({ type: 'error', message: 'Nomor port stasiun tidak valid.' });
        return;
      }
      if (!pass) {
        sendJson({
          type: 'error',
          message: 'Kata sandi stream belum diatur. Isi kata sandi di menu Pengaturan.',
        });
        return;
      }

      let resolved;
      try {
        resolved = await resolvePublicHost(rawHost);
      } catch (err) {
        sendJson({ type: 'error', message: err.message || 'Alamat server tidak valid.' });
        return;
      }

      if (ws.readyState !== 1) return;

      let upstreamInfo;
      try {
        upstreamInfo = await connectBroadcastUpstream({
          ip: resolved.ip,
          family: resolved.family,
          host: resolved.host,
          port,
          user,
          pass,
          mount,
          station,
          genre,
          bitrate,
        });
      } catch (err) {
        sendJson({ type: 'error', message: err.message || 'Gagal tersambung ke server siaran.' });
        return;
      }

      if (ws.readyState !== 1 || activeBroadcast) {
        upstreamInfo.socket.destroy();
        if (activeBroadcast) {
          sendJson({ type: 'error', message: 'Sedang ada penyiar lain yang aktif.' });
        }
        return;
      }

      const ff = spawn(
        'ffmpeg',
        [
          '-hide_banner',
          '-loglevel',
          'error',
          '-f',
          's16le',
          '-ar',
          String(sampleRate),
          '-ac',
          '1',
          '-i',
          'pipe:0',
          '-c:a',
          'libmp3lame',
          '-b:a',
          `${bitrate}k`,
          '-ar',
          String(outSampleRate),
          '-ac',
          '1',
          '-f',
          'mp3',
          'pipe:1',
        ],
        { shell: false, stdio: ['pipe', 'pipe', 'pipe'] }
      );

      let bytesSent = 0;
      let ffErrBuf = '';
      const startedAt = Date.now();

      session = {
        cleanup: cleanupSession,
        ff,
        upstream: upstreamInfo.socket,
        mode: upstreamInfo.mode,
        mount: upstreamInfo.mount || '/stream',
        ip: resolved.ip,
        host: resolved.host,
        port: upstreamInfo.connectedPort || port,
        httpPort: upstreamInfo.mode === 'shoutcast' && upstreamInfo.connectedPort === port + 1 ? port : (upstreamInfo.connectedPort || port),
        user,
        pass,
        statsTimer: setInterval(() => {
          sendJson({
            type: 'stats',
            bytesSent,
            uptimeSec: Math.floor((Date.now() - startedAt) / 1000),
          });
        }, 1500),
      };
      activeBroadcast = session;

      ff.stdout.on('data', (mp3Chunk) => {
        if (!session || session.upstream.destroyed || !session.upstream.writable) return;
        bytesSent += mp3Chunk.length;
        session.upstream.write(mp3Chunk);
      });

      ff.stderr.on('data', (chunk) => {
        if (ffErrBuf.length < 400) ffErrBuf += chunk.toString('utf8');
      });

      ff.on('error', () => {
        cleanupSession('Encoder audio bermasalah.');
      });

      ff.on('close', (code) => {
        if (session) {
          const detail = code && ffErrBuf.trim() ? ` (${ffErrBuf.trim().slice(0, 120)})` : '';
          cleanupSession(`Siaran berakhir${detail}.`);
        }
      });

      upstreamInfo.socket.on('error', () => {
        cleanupSession('Sambungan ke server siaran terputus.');
      });

      upstreamInfo.socket.on('close', () => {
        if (session) cleanupSession('Server siaran menutup sambungan.');
      });

      sendJson({
        type: 'live',
        host: resolved.host,
        port: upstreamInfo.connectedPort || port,
        bitrate,
        mode: upstreamInfo.mode,
      });

      if (title) {
        updateUpstreamMetadata({
          ip: resolved.ip,
          host: resolved.host,
          port: session.httpPort,
          user,
          pass,
          title,
          mode: upstreamInfo.mode,
          mount: upstreamInfo.mount,
        }).catch(() => {});
      }
    }
  });

  ws.on('close', () => {
    cleanupSession();
  });
});

server.listen(PORT, HOST, () => {
  console.log(`Radio Islam Sragen server listening on http://${HOST}:${PORT}`);
});
