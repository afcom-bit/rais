import express from 'express';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = Number(process.env.PORT) || 3000;
const HOST = '0.0.0.0';

const STREAM_HOST = process.env.STREAM_HOST || process.env.BROADCAST_HOST || 'streamy1.radioislam.my.id';
const STREAM_PORT = Number(process.env.STREAM_PORT || process.env.BROADCAST_PORT) || 9210;

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

const BLOCKED_STATIC_PATHS = new Set([
  '/server.js',
  '/package.json',
  '/package-lock.json',
  '/bun.lock',
  '/vercel.json',
  '/.vercelignore',
  '/.env',
  '/.env.example',
]);

app.use((req, res, next) => {
  const reqPath = (req.path || '').toLowerCase();
  if (
    reqPath.startsWith('/.') ||
    BLOCKED_STATIC_PATHS.has(reqPath)
  ) {
    res.status(404).end();
    return;
  }
  next();
});

app.use(express.static(__dirname, { extensions: ['html'], dotfiles: 'deny' }));

app.listen(PORT, HOST, () => {
  console.log(`Radio Islam Sragen server listening on http://${HOST}:${PORT}`);
});
