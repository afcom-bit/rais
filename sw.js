/* Service worker RAIS. Ubah VERSI setiap kali file aplikasi diperbarui agar cache lama diganti. */
var VERSI = 'rais-v1';
var INTI = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './icons/apple-touch-icon.png'
];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(VERSI).then(function (c) { return c.addAll(INTI); }));
  self.skipWaiting();
});

self.addEventListener('activate', function (e) {
  e.waitUntil(
    caches.keys()
      .then(function (ks) { return Promise.all(ks.filter(function (k) { return k !== VERSI; }).map(function (k) { return caches.delete(k); })); })
      .then(function () { return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function (e) {
  var r = e.request;
  /* Hanya file milik aplikasi ini. Iframe player dan stream radio (domain lain) tidak disentuh. */
  if (r.method !== 'GET' || new URL(r.url).origin !== self.location.origin) return;

  if (r.mode === 'navigate') {
    /* Halaman: ambil dari jaringan dulu, kalau offline pakai cache */
    e.respondWith(
      fetch(r).then(function (res) {
        var salin = res.clone();
        caches.open(VERSI).then(function (c) { c.put('./index.html', salin); });
        return res;
      }).catch(function () { return caches.match('./index.html'); })
    );
    return;
  }

  /* File lain: pakai cache langsung, perbarui di latar belakang */
  e.respondWith(
    caches.match(r).then(function (hit) {
      var jaringan = fetch(r).then(function (res) {
        if (res && res.ok) { var salin = res.clone(); caches.open(VERSI).then(function (c) { c.put(r, salin); }); }
        return res;
      }).catch(function () { return hit; });
      return hit || jaringan;
    })
  );
});
