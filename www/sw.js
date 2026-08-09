// Vinylist offline bootstrapper — Francis&M_Tech
// Cache-first service worker: once a dependency is cached, it is served
// from CacheStorage forever and never re-requested from the network.
//
// CACHE_NAME and CORE_ASSETS live in ./shell-manifest.js, shared with
// index.html, so the two can never fall out of sync with each other.

importScripts('./shell-manifest.js');
const CACHE_NAME = self.VINYLIST_SHELL.CACHE_NAME;
const CORE_ASSETS = self.VINYLIST_SHELL.CORE_ASSETS;
const CACHEABLE_HOSTS = self.VINYLIST_SHELL.CACHEABLE_HOSTS;

// Fetch+cache one asset, retrying a couple of times before giving up.
// Precache "succeeding" for every asset is what lets the app run offline
// forever from the very first launch — so this tries harder than a single
// best-effort fetch.
async function precacheOne(cache, url, attempts) {
  for (let i = 0; i < attempts; i++) {
    try {
      const res = await fetch(url, { mode: url.startsWith('http') ? 'cors' : 'same-origin', cache: 'no-store' });
      if (res && (res.ok || res.type === 'opaque')) {
        await cache.put(url, res);
        return true;
      }
    } catch (err) {
      /* offline or network hiccup — retry below, or give up after last attempt */
    }
    if (i < attempts - 1) await new Promise((r) => setTimeout(r, 800));
  }
  return false;
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE_NAME);
      await Promise.all(CORE_ASSETS.map((url) => precacheOne(cache, url, 3)));
      // First-ever install on this device: nothing running yet to preserve
      // continuity with, so activate right away. Any LATER version instead
      // stays "waiting" here until the page explicitly approves it (see the
      // SKIP_WAITING message handler below) — that's what lets Settings show
      // an "Update available" button the person taps on their own schedule,
      // rather than the app silently swapping itself out from under them.
      if (!self.registration.active) self.skipWaiting();
    })()
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n)));
      await self.clients.claim();
      broadcast('SHELL_ACTIVE');
    })()
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  const isSameOrigin = url.origin === self.location.origin;
  const isCDN = CACHEABLE_HOSTS.some((h) => url.hostname === h || url.hostname.endsWith('.' + h));
  if (!isSameOrigin && !isCDN) return; // not ours to manage — let the browser handle it normally

  event.respondWith(
    (async () => {
      const cache = await caches.open(CACHE_NAME);
      const cached = await cache.match(req, { ignoreVary: true, ignoreSearch: false });
      if (cached) return cached; // cache-first: never re-fetch something we already have

      try {
        const res = await fetch(req);
        if (res && (res.ok || res.type === 'opaque')) {
          cache.put(req, res.clone());
        }
        return res;
      } catch (err) {
        if (cached) return cached;
        throw err;
      }
    })()
  );
});

// Lets the page ask "how many core assets are already cached?" for the
// splash screen's progress readout, and broadcasts state changes so the
// page doesn't have to poll.
async function broadcast(message) {
  const clients = await self.clients.matchAll({ includeUncontrolled: true });
  clients.forEach((c) => c.postMessage(message));
}

self.addEventListener('message', (event) => {
  if (event.data === 'PING') {
    event.source && event.source.postMessage('PONG');
  }
  if (event.data === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});
