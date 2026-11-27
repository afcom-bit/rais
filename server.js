import express from 'express';
import http from 'node:http';
import net from 'node:net';
import dns from 'node:dns/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = Number(process.env.PORT) || 3000;
const HOST = '0.0.0.0';

const STREAM_HOST = process.env.BROADCAST_HOST || 'streamy1.radioislam.my.id';
const STREAM_PORT = Number(process.env.BROADCAST_PORT) || 9210;
const BROADCAST_USER = process.env.BROADCAST_USER || 'sragen';

const ALLOWED_BITRATES = new Set([24, 32, 48, 64, 96, 128]);

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
app.use((req, res, next) => {
  res.setHeader('Permissions-Policy', 'microphone=(self)');
  next();
});
app.use(express.json({ limit: '16kb' }));

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
    if (p[0] === 0) return true; // 0.0.0.0/8
    if (p[0] === 10) return true; // 10.0.0.0/8
    if (p[0] === 100 && p[1] >= 64 && p[1] <= 127) return true; // CGNAT 100.64.0.0/10
    if (p[0] === 127) return true; // Loopback 127.0.0.0/8
    if (p[0] === 169 && p[1] === 254) return true; // Link-local 169.254.0.0/16
    if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return true; // Private 172.16.0.0/12
    if (p[0] === 192 && p[1] === 168) return true; // Private 192.168.0.0/16
    if (p[0] >= 224) return true; // Multicast & reserved
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

let activeBroadcast = null;

app.get('/api/broadcast/config', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.json({
    host: STREAM_HOST,
    port: STREAM_PORT,
    user: BROADCAST_USER,
    hasPass: Boolean(process.env.BROADCAST_PASS && process.env.BROADCAST_PASS.length > 0),
    station: 'Radio Islam Sragen',
    genre: 'Islamic',
    bitrate: 48,
    busy: Boolean(activeBroadcast),
  });
});

app.use(express.static(__dirname, { extensions: ['html'] }));

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
    return parsed.host.toLowerCase() === expectedHost;
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

function connectShoutcastV1({ ip, family, port, user, pass, station, genre, bitrate }) {
  return new Promise((resolve, reject) => {
    const sourcePort = port + 1;
    if (sourcePort > 65535) {
      reject(new Error('Port sumber Shoutcast di luar batas.'));
      return;
    }
    const sock = net.connect({ host: ip, port: sourcePort, family });
    let settled = false;
    let respBuf = '';

    const fail = (err) => {
      if (settled) return;
      settled = true;
      sock.destroy();
      reject(err);
    };

    sock.setTimeout(8000, () => {
      fail(new Error('Waktu sambung ke port siaran habis.'));
    });

    sock.on('error', (err) => {
      fail(err);
    });

    sock.on('connect', () => {
      const authLine = user ? `${user}:${pass}` : pass;
      sock.write(`${authLine}\r\n`);
    });

    sock.on('data', (chunk) => {
      if (settled) return;
      respBuf += chunk.toString('utf8');
      if (respBuf.length > 2048) {
        fail(new Error('Respon server siaran tidak dikenal.'));
        return;
      }
      if (!respBuf.includes('\n')) return;

      const lower = respBuf.toLowerCase();
      if (lower.includes('invalid password') || lower.includes('invalid')) {
        fail(new Error('User atau kata sandi siaran salah.'));
        return;
      }
      if (lower.includes('already in use')) {
        fail(new Error('Siaran sedang digunakan oleh penyiar lain.'));
        return;
      }
      if (respBuf.startsWith('OK2') || respBuf.startsWith('OK')) {
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
        resolve({ socket: sock, mode: 'shoutcast' });
        return;
      }
      fail(new Error(`Server menolak sambungan: ${respBuf.trim()}`));
    });

    sock.on('close', () => {
      if (!settled) fail(new Error('Sambungan ditutup oleh server siaran.'));
    });
  });
}

function connectIcecastSource({ ip, family, host, port, user, pass, mount, station, genre, bitrate }) {
  return new Promise((resolve, reject) => {
    const cleanMount = '/' + String(mount || 'stream').replace(/^\/+/, '').replace(/[\r\n\0\s]+/g, '');
    const sock = net.connect({ host: ip, port, family });
    let settled = false;
    let respBuf = '';

    const fail = (err) => {
      if (settled) return;
      settled = true;
      sock.destroy();
      reject(err);
    };

    sock.setTimeout(8000, () => fail(new Error('Waktu sambung Icecast habis.')));
    sock.on('error', (err) => fail(err));

    sock.on('connect', () => {
      const auth = Buffer.from(`${user || 'source'}:${pass}`).toString('base64');
      const reqHeaders = [
        `SOURCE ${cleanMount} HTTP/1.0`,
        `Host: ${host}:${port}`,
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
        fail(new Error('Respon Icecast tidak dikenal.'));
        return;
      }
      if (!respBuf.includes('\r\n\r\n') && !respBuf.includes('\n\n') && !respBuf.includes('\n')) return;
      const firstLine = respBuf.split(/\r?\n/)[0] || '';
      if (/^HTTP\/1\.[01]\s+200\b/i.test(firstLine)) {
        settled = true;
        sock.setTimeout(0);
        sock.removeAllListeners('data');
        resolve({ socket: sock, mode: 'icecast', mount: cleanMount });
        return;
      }
      if (/401|403/.test(firstLine)) {
        fail(new Error('User atau kata sandi Icecast salah.'));
        return;
      }
      fail(new Error(`Server Icecast menolak: ${firstLine || respBuf.trim()}`));
    });

    sock.on('close', () => {
      if (!settled) fail(new Error('Sambungan Icecast ditutup.'));
    });
  });
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
          port: session.port,
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

      const rawHost = cleanHeaderValue(msg.host, STREAM_HOST, 253);
      const port = Number(msg.port);
      const user = cleanHeaderValue(msg.user, BROADCAST_USER, 64);
      const pass =
        typeof msg.pass === 'string' && msg.pass.length > 0
          ? msg.pass.replace(/[\r\n\0]+/g, '').slice(0, 128)
          : (process.env.BROADCAST_PASS || '');
      const bitrate = ALLOWED_BITRATES.has(Number(msg.bitrate)) ? Number(msg.bitrate) : 48;
      const rawRate = Math.round(Number(msg.sampleRate));
      const sampleRate = Number.isInteger(rawRate) && rawRate >= 8000 && rawRate <= 96000 ? rawRate : 44100;
      const station = cleanHeaderValue(msg.station, 'Radio Islam Sragen', 100);
      const genre = cleanHeaderValue(msg.genre, 'Islamic', 48);
      const title = cleanHeaderValue(msg.title, '', 180);
      const mount = cleanHeaderValue(msg.mount, '/stream', 64);

      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        sendJson({ type: 'error', message: 'Nomor port tidak valid.' });
        return;
      }
      if (!pass) {
        sendJson({
          type: 'error',
          message: 'Kata sandi stream belum diisi. Masukkan sandi atau atur BROADCAST_PASS di server.',
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
        try {
          upstreamInfo = await connectShoutcastV1({
            ip: resolved.ip,
            family: resolved.family,
            port,
            user,
            pass,
            station,
            genre,
            bitrate,
          });
        } catch (scErr) {
          const msgLower = String(scErr.message || '').toLowerCase();
          if (msgLower.includes('salah') || msgLower.includes('digunakan')) {
            throw scErr;
          }
          upstreamInfo = await connectIcecastSource({
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
        }
      } catch (err) {
        sendJson({ type: 'error', message: err.message || 'Gagal tersambung ke server siaran.' });
        return;
      }

      if (ws.readyState !== 1 || activeBroadcast) {
        upstreamInfo.socket.destroy();
        if (activeBroadcast) sendJson({ type: 'error', message: 'Sedang ada penyiar lain yang aktif.' });
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
          '44100',
          '-ac',
          '1',
          '-f',
          'mp3',
          'pipe:1',
        ],
        { shell: false, stdio: ['pipe', 'pipe', 'pipe'] }
      );

      let bytesSent = 0;
      const startedAt = Date.now();

      session = {
        ff,
        upstream: upstreamInfo.socket,
        mode: upstreamInfo.mode,
        mount: upstreamInfo.mount || '/stream',
        ip: resolved.ip,
        host: resolved.host,
        port,
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

      ff.stderr.on('data', () => {});

      ff.on('error', () => {
        cleanupSession('Encoder audio bermasalah.');
      });

      ff.on('close', () => {
        if (session) cleanupSession('Siaran berakhir.');
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
        port,
        bitrate,
        mode: upstreamInfo.mode,
      });

      if (title) {
        updateUpstreamMetadata({
          ip: resolved.ip,
          host: resolved.host,
          port,
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
